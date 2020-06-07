package com.patchworkmc.transformer;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import com.patchworkmc.Patchwork;
import com.patchworkmc.access.AccessTransformation;
import com.patchworkmc.access.ClassAccessTransformations;
import com.patchworkmc.access.ModAccessTransformer;
import com.patchworkmc.annotation.AnnotationProcessor;
import com.patchworkmc.annotation.AnnotationStorage;
import com.patchworkmc.event.EventBusSubscriber;
import com.patchworkmc.event.EventSubclassTransformer;
import com.patchworkmc.event.EventHandlerRewriter;
import com.patchworkmc.event.SubscribeEvent;
import com.patchworkmc.event.initialization.RegisterAutomaticSubscribers;
import com.patchworkmc.event.initialization.RegisterEventRegistrars;
import com.patchworkmc.event.EventSubscriptionChecker;
import com.patchworkmc.patch.StringConstantRemapper;
import com.patchworkmc.mapping.remapper.PatchworkRemapper;
import com.patchworkmc.objectholder.ObjectHolder;
import com.patchworkmc.objectholder.ObjectHolderGenerator;
import com.patchworkmc.objectholder.ObjectHolderScanner;
import com.patchworkmc.objectholder.initialization.RegisterObjectHolders;
import com.patchworkmc.patch.BlockSettingsTransformer;
import com.patchworkmc.patch.ExtensibleEnumTransformer;
import com.patchworkmc.patch.ItemGroupTransformer;
import com.patchworkmc.transformer.initialization.ConstructTargetMod;

public class PatchworkTransformer implements BiConsumer<String, byte[]> {
	private static final Logger LOGGER = Patchwork.LOGGER;

	private final BiConsumer<String, byte[]> outputConsumer;
	private final PatchworkRemapper remapper;

	// Queues are used instead of another collection type because they have concurrency
	private final Queue<Map.Entry<String, ObjectHolder>> generatedObjectHolderEntries = new ConcurrentLinkedQueue<>(); // shimName -> ObjectHolder
	private final Set<String> classesWithStaticEvents = ConcurrentHashMap.newKeySet();
	private final Set<String> classesWithInstanceEvents = ConcurrentHashMap.newKeySet();
	private final Queue<Map.Entry<String, EventBusSubscriber>> eventBusSubscribers = new ConcurrentLinkedQueue<>(); // basename -> EventBusSubscriber
	private final Queue<Map.Entry<String, String>> modInfo = new ConcurrentLinkedQueue<>(); // modId -> clazz

	private final EventSubscriptionChecker checker = new EventSubscriptionChecker();
	private final AnnotationStorage annotationStorage;

	private boolean finished;

	/**
	 * The main class transformer for Patchwork.
	**/
	public PatchworkTransformer(BiConsumer<String, byte[]> outputConsumer, PatchworkRemapper remapper, AnnotationStorage annotationStorage) {
		this.outputConsumer = outputConsumer;
		this.remapper = remapper;
		this.annotationStorage = annotationStorage;
		this.finished = false;
	}

	@Override
	public void accept(String name, byte[] content) {
		// Names should match Java internal names, such as "java/lang/Object" or "com/example/Example$1"

		if (name.startsWith("/")) {
			throw new IllegalArgumentException("Name should not start with a /");
		}

		if (name.endsWith(".class")) {
			throw new IllegalArgumentException("Name should not end with .class");
		}

		if (name.startsWith("net/minecraft")) {
			throw new IllegalArgumentException("Mod jars are not allowed to contain classes in Minecraft's package!");
		}

		if (name.startsWith("java")) {
			throw new IllegalArgumentException("Mod jars are not allowed to contain classes in Java's package!");
		}

		ClassReader reader = new ClassReader(content);
		ClassNode node = new ClassNode();

		List<ObjectHolder> objectHolders = new ArrayList<>();
		List<SubscribeEvent> subscribeEvents = new ArrayList<>();
		AtomicReference<EventBusSubscriber> eventBusSubscriber = new AtomicReference<>();

		ClassAccessTransformations accessTransformations = new ClassAccessTransformations();

		Consumer<String> modConsumer = classModId -> {
			LOGGER.trace("Found @Mod annotation at %s (id: %s)", name, classModId);
			modInfo.add(new AbstractMap.SimpleImmutableEntry<>(classModId, name));
		};

		AnnotationProcessor scanner = new AnnotationProcessor(node, modConsumer, annotationStorage);
		ObjectHolderScanner objectHolderScanner = new ObjectHolderScanner(scanner, holder -> {
			objectHolders.add(holder);

			accessTransformations.addFieldTransformation(holder.getField(), AccessTransformation.DEFINALIZE_MAKE_PUBLIC);
		});

		EventHandlerRewriter eventHandlerRewriter = new EventHandlerRewriter(objectHolderScanner, eventBusSubscriber::set,
				subscribeEvent -> {
			subscribeEvents.add(subscribeEvent);

			accessTransformations.setClassTransformation(AccessTransformation.MAKE_PUBLIC);

			accessTransformations.addMethodTransformation(subscribeEvent.getMethod(), subscribeEvent.getMethodDescriptor(), AccessTransformation.MAKE_PUBLIC);
		});

		ItemGroupTransformer itemGroupTransformer = new ItemGroupTransformer(eventHandlerRewriter);
		BlockSettingsTransformer blockSettingsTransformer = new BlockSettingsTransformer(itemGroupTransformer);
		ExtensibleEnumTransformer extensibleEnumTransformer = new ExtensibleEnumTransformer(blockSettingsTransformer);
		EventSubclassTransformer eventSubclassTransformer = new EventSubclassTransformer(extensibleEnumTransformer);

		reader.accept(eventSubclassTransformer, ClassReader.EXPAND_FRAMES);

		ClassWriter writer = new ClassWriter(0);

		ModAccessTransformer accessTransformer = new ModAccessTransformer(writer, accessTransformations);

		StringConstantRemapper stringRemapper = new StringConstantRemapper(accessTransformer, remapper.getNaiveRemapper());
		node.accept(stringRemapper);

		objectHolders.forEach(entry -> {
			ClassWriter shimWriter = new ClassWriter(0);
			String shimName = ObjectHolderGenerator.generate(name, entry, shimWriter);

			generatedObjectHolderEntries.add(new AbstractMap.SimpleImmutableEntry<>(shimName, entry));

			outputConsumer.accept(shimName, shimWriter.toByteArray());
		});

		boolean addedStaticEvent = false;

		for (SubscribeEvent entry : subscribeEvents) {
			if ((entry.getAccess() & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) {
				classesWithStaticEvents.add(name);
				addedStaticEvent = true;
			} else {
				classesWithInstanceEvents.add(name);
			}
		}

		if (eventBusSubscriber.get() != null) {
			if (!addedStaticEvent) {
				Patchwork.LOGGER.warn("Ignoring the @EventBusSubscriber annotation on %s because it has no static methods with @SubscribeEvent", name);
			} else {
				EventBusSubscriber subscriber = eventBusSubscriber.get();

				eventBusSubscribers.add(new AbstractMap.SimpleImmutableEntry<>(name, subscriber));
			}
		}

		outputConsumer.accept(name, writer.toByteArray());

		List<String> supers = new ArrayList<>();
		supers.add(reader.getSuperName());
		supers.addAll(Arrays.asList(reader.getInterfaces()));
		checker.onClassScanned(name, subscribeEvents, supers);
	}

	/**
	 * Finishes the patching process.
	 *
	 * @param entrypoints outputs the list of entrypoints for the fabric.mod.json
	 * @return the primary mod id
	 */
	public String finish(Consumer<String> entrypoints) {
		if (finished) {
			throw new IllegalStateException("Already finished!");
		}

		this.finished = true;

		if (modInfo.isEmpty()) {
			throw new IllegalStateException("Located no classes with an @Mod annotation, could not pick a primary mod!");
		}

		Map.Entry<String, String> primary = modInfo.peek();
		String primaryId = primary.getKey();
		String primaryClazz = primary.getValue();

		generateInitializer(primaryId, primaryClazz, entrypoints);

		classesWithStaticEvents.clear();
		classesWithInstanceEvents.clear();
		eventBusSubscribers.clear();
		generatedObjectHolderEntries.clear();

		modInfo.forEach(entry -> {
			if (entry.getKey().equals(primaryId)) {
				return;
			}

			generateInitializer(entry.getKey(), entry.getValue(), entrypoints);
		});

		checker.check();

		return primaryId;
	}

	private void generateInitializer(String id, String clazz, Consumer<String> entrypoints) {
		ClassWriter initializerWriter = new ClassWriter(0);
		String initializerName = "patchwork_generated/" + clazz + "Initializer";

		List<Map.Entry<String, Consumer<MethodVisitor>>> initializerSteps = new ArrayList<>();

		// TODO: Need to check if the base classes are annotated with @OnlyIn / @Environment

		initializerSteps.add(new AbstractMap.SimpleImmutableEntry<>("registerEventRegistrars", new RegisterEventRegistrars(classesWithStaticEvents, classesWithInstanceEvents)));
		// TODO: This should probably be first? How do we do event registrars without classloading the target class?
		initializerSteps.add(new AbstractMap.SimpleImmutableEntry<>("constructTargetMod", new ConstructTargetMod(clazz)));
		initializerSteps.add(new AbstractMap.SimpleImmutableEntry<>("registerAutomaticSubscribers", new RegisterAutomaticSubscribers(eventBusSubscribers)));
		initializerSteps.add(new AbstractMap.SimpleImmutableEntry<>("registerObjectHolders", new RegisterObjectHolders(generatedObjectHolderEntries)));

		ForgeInitializerGenerator.generate(initializerName, id, initializerSteps, initializerWriter);

		entrypoints.accept(initializerName.replace('/', '.'));
		outputConsumer.accept(initializerName, initializerWriter.toByteArray());
	}
}
