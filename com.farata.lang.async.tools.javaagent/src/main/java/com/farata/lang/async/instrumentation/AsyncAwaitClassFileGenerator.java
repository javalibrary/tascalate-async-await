
package com.farata.lang.async.instrumentation;

import static org.objectweb.asm.Opcodes.*;

import java.lang.instrument.IllegalClassFormatException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

public class AsyncAwaitClassFileGenerator {
	
	final private static Log log = LogFactory.getLog(AsyncAwaitClassFileGenerator.class);
	
	final private static String ASYNC_ANNOTATION_DESCRIPTOR = "Lcom/farata/lang/async/api/async;";
	final private static String ASYNC_TASK_NAME = "com/farata/lang/async/core/AsyncTask";
	
	final private static String ASYNC_CALL_NAME = "com/farata/lang/async/api/AsyncCall";
	final private static String ASYNC_EXECUTOR_NAME = "com/farata/lang/async/core/AsyncExecutor";
	
	final private static String COMPLETABLE_FUTURE_NAME = "java/util/concurrent/CompletableFuture";
	final private static String COMPLETION_STAGE_DESCRIPTOR = "Ljava/util/concurrent/CompletionStage;";
	
	final private static String CONTINUABLE_ANNOTATION_DESCRIPTOR = "Lorg/apache/commons/javaflow/api/continuable;";
	
	// New generated classes
	final private List<ClassNode> newClasses = new ArrayList<ClassNode>();
	
	// Original method's "method name + method desc" -> Access method's MethodNode
	final private Map<String, MethodNode> accessMethods = new HashMap<String, MethodNode>();
	
	private void registerAccessMethod(final String owner, final String name, final String desc, final String kind, final MethodNode methodNode) {
		accessMethods.put(owner + name + desc + "-" + kind, methodNode);
	}
	
	private MethodNode getAccessMethod(final String owner, final String name, final String desc, final String kind) {
		return accessMethods.get(owner + name + desc + "-" + kind);
	}
	
	public byte[] transform(final String className, final byte[] classfileBuffer) throws IllegalClassFormatException {
		// Read
		final ClassReader classReader = new ClassReader(classfileBuffer);
		final ClassNode classNode = new ClassNode();
		classReader.accept(classNode, 0);

		// Transform
		if (!transform(classNode)) {
			// no modification, delegate further
			return null;
		}
		
		// Print transformed class
		log.debug("Transformed class:\n\n" + BytecodeTraceUtil.toString(classNode) + "\n\n");
		
		// Print generated classes
		for (final ClassNode newClass : newClasses) {
			log.debug("Generated class:\n\n" + BytecodeTraceUtil.toString(newClass) + "\n\n");
		}
		
		// Write
		final byte[] generatedClassBytes;
		{
			final ClassWriter cw = new ClassWriter(classReader, 0);
			classNode.accept(cw);
			generatedClassBytes = cw.toByteArray();
		}
		return generatedClassBytes;
	}
	
	public Map<String, byte[]> getGeneratedClasses() {
		final Map<String, byte[]> result = new HashMap<String, byte[]>();
		for (final ClassNode classNode : newClasses) {
			final ClassWriter cw = new ClassWriter(0);
			classNode.accept(cw);
			final byte[] b = cw.toByteArray();
			result.put(classNode.name, b);
		}
		return result;
	}
	
	public void reset() {
		accessMethods.clear();
		newClasses.clear();
	}
	
	protected boolean transform(final ClassNode classNode) {
		boolean transformed = false;
		final List<InnerClassNode> originalInnerClasses = new ArrayList<InnerClassNode>(innerClassesOf(classNode));
		for (final MethodNode methodNode : new ArrayList<MethodNode>(methodsOf(classNode))) {
			if (isAsyncMethod(methodNode)) {
				transform(classNode, originalInnerClasses, methodNode);
				transformed = true;
			}
		}
		return transformed;
	}

	protected void transform(final ClassNode classNode, final List<InnerClassNode> originalInnerClasses, final MethodNode originalAsyncMethodNode) {
		log.info("Transforming blocking method: " + classNode.name + "." + originalAsyncMethodNode.name + originalAsyncMethodNode.desc);
		
		// Remove @async annotation
		removeAsyncAnnotation(originalAsyncMethodNode);
		classNode.visitAnnotation(CONTINUABLE_ANNOTATION_DESCRIPTOR, true);
		
		// Create InnerClassNode for anoymous class
		final String asyncTaskClassName = createInnerClassName(classNode);
		innerClassesOf(classNode).add(new InnerClassNode(asyncTaskClassName, null, null, 0));
		
		// Create accessor methods
		createAccessMethodsForAsyncMethod(classNode, originalAsyncMethodNode);
		
		// Create ClassNode for anonymous class
		final ClassNode asyncTaskClassNode = createAnonymousClass(classNode, originalInnerClasses, originalAsyncMethodNode, asyncTaskClassName);
		newClasses.add(asyncTaskClassNode);
		
		// Replace original method 
		
		final MethodNode replacementAsyncMethodNode = createReplacementAsyncMethod(classNode, originalAsyncMethodNode, asyncTaskClassName);
		final List<MethodNode> methods = methodsOf(classNode);
		methods.set(methods.indexOf(originalAsyncMethodNode), replacementAsyncMethodNode);
		
	}
	
	protected ClassNode createAnonymousClass(final ClassNode originalClassNode, final List<InnerClassNode> originalInnerClasses, final MethodNode originalAsyncMethodNode, final String asyncClassName) {
		final ClassNode asyncClassNode = new ClassNode();
		
		asyncClassNode.visit(originalClassNode.version, ACC_SUPER, asyncClassName, null, ASYNC_TASK_NAME, new String[]{});
		asyncClassNode.visitAnnotation(CONTINUABLE_ANNOTATION_DESCRIPTOR, true);
		asyncClassNode.visitSource(originalClassNode.sourceFile, null);
		asyncClassNode.visitOuterClass(originalClassNode.name, originalAsyncMethodNode.name, originalAsyncMethodNode.desc);
		
		// Copy outer class inner classes
		final List<InnerClassNode> asyncClassInnerClasses = innerClassesOf(asyncClassNode);
		for (final InnerClassNode innerClassNode : originalInnerClasses) {
			asyncClassInnerClasses.add(innerClassNode);
		}
		
		// SerialVersionUID
		asyncClassNode.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "serialVersionUID", "J", null, new Long(1L));
		
		// Outer class instance field
		final FieldNode outerClassField = (FieldNode) asyncClassNode.visitField(ACC_FINAL + ACC_PRIVATE + ACC_SYNTHETIC, "this$0", "L" + originalClassNode.name + ";", null, null);
		
		// Original methods arguments
		final Type[] argTypes = Type.getArgumentTypes(originalAsyncMethodNode.desc);
		final int originalArity = argTypes.length;
		{
			for (int i = 0; i < originalArity; i++) {
				String argName = createOuterClassMethodArgFieldName(i);
				String argDesc = argTypes[i].getDescriptor();
				asyncClassNode.visitField(ACC_PRIVATE + ACC_FINAL + ACC_SYNTHETIC, argName, argDesc, null, null);
			}
		}
		
		// Constructor taking the outer class instance and original method's arguments
		{
			final Type[] constructorArgTypes = prependArray(
				argTypes, 
				Type.getReturnType("L" + originalClassNode.name + ";"),
				Type.getReturnType(COMPLETION_STAGE_DESCRIPTOR)
			);
			final String constructorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, constructorArgTypes);
			
			final MethodVisitor mv = asyncClassNode.visitMethod(0, "<init>", constructorDesc, null, null);
			mv.visitCode();

			// Store outer class instance
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ALOAD, 1);
			mv.visitFieldInsn(PUTFIELD, asyncClassName, outerClassField.name, outerClassField.desc);
			
			
			// Store original method's arguments
			for (int i = 0; i < originalArity; i++) {
				String argName = createOuterClassMethodArgFieldName(i);
				String argDesc = argTypes[i].getDescriptor();
				
				mv.visitVarInsn(ALOAD, 0);
				mv.visitVarInsn(argTypes[i].getOpcode(ILOAD), 3 + i);
				mv.visitFieldInsn(PUTFIELD, asyncClassName, argName, argDesc);
			}

			// Invoke super() 
			mv.visitVarInsn(ALOAD, 0);
			mv.visitVarInsn(ALOAD, 2);
			mv.visitMethodInsn(INVOKESPECIAL, ASYNC_TASK_NAME, "<init>", "(" + COMPLETION_STAGE_DESCRIPTOR + ")V", false);
		
			mv.visitInsn(RETURN);
			mv.visitMaxs(2, 3 + argTypes.length);
			mv.visitEnd();
		}
		addAnonymousClassRunMethod(originalClassNode, originalAsyncMethodNode, asyncClassNode, outerClassField);
		return asyncClassNode;
	}

	protected MethodNode addAnonymousClassRunMethod(ClassNode classNode, MethodNode methodNode, ClassNode acn, FieldNode outerClassField) {
		final Type[] argTypes = Type.getArgumentTypes(methodNode.desc);
		log.debug("Method has " + argTypes.length + " arguments");
		
		final MethodNode amn = (MethodNode) acn.visitMethod(ACC_PUBLIC, "run", "()V", null, new String[] {});
		amn.visitAnnotation(CONTINUABLE_ANNOTATION_DESCRIPTOR, true);

		// Local variables
		//amn.localVariables = methodNode.localVariables;
		
		final LabelNode methodStart = new LabelNode();
		final LabelNode beforeExceptionHandler = new LabelNode();
		final LabelNode insideExceptionHandler = new LabelNode();
		final LabelNode methodEnd = new LabelNode();
		
		
		@SuppressWarnings("unchecked")
		final List<TryCatchBlockNode> tryCatchBlocks = amn.tryCatchBlocks;
		// Try-catch blocks
		for (final Iterator<?> it = methodNode.tryCatchBlocks.iterator(); it.hasNext();) {
			final TryCatchBlockNode tn = (TryCatchBlockNode) it.next();
			tryCatchBlocks.add(tn);
		}
		// Should be the latest -- surrounding try-catch-all
		tryCatchBlocks.add(new TryCatchBlockNode(methodStart, beforeExceptionHandler, insideExceptionHandler, "java/lang/Throwable"));
		//amn.localVariables.add(new LocalVariableNode("ex", "java/lang/Throwable", "Ljava/lang/Throwable;", insideExceptionHandler, methodEnd, amn.localVariables.size()));

		
		final InsnList newInstructions = new InsnList();
		newInstructions.add(methodStart);
		// Instructions
		for (final Iterator<?> it = methodNode.instructions.iterator(); it.hasNext();) {
			final AbstractInsnNode instruction = (AbstractInsnNode) it.next();
			
			if (instruction instanceof VarInsnNode) {
				final VarInsnNode vin = (VarInsnNode) instruction;
				// "this" -> outer class "this"
				if (vin.getOpcode() == ALOAD && vin.var == 0) {
					log.debug("Found " + BytecodeTraceUtil.toString(vin));
					
					newInstructions.add(instruction);
					newInstructions.add(new FieldInsnNode(GETFIELD, acn.name, outerClassField.name, outerClassField.desc));
					continue;
				}
				
				// original method had arguments
				if (argTypes.length > 0 && vin.getOpcode() != RET && vin.var > 0) {
					log.debug("Found " + BytecodeTraceUtil.toString(vin));
					// method argument -> inner class field 
					if (vin.var <= argTypes.length) {
						int i = vin.var - 1;	// method argument's index
						String argName = createOuterClassMethodArgFieldName(i);
						String argDesc = Type.getMethodDescriptor(argTypes[i], new Type[0]).substring(2);
						
						newInstructions.add(new VarInsnNode(ALOAD, 0));
						if (isLoadOpcode(vin.getOpcode())) {
							assert (argTypes[i].getOpcode(ILOAD) == vin.getOpcode()) :
								"Wrong opcode " + vin.getOpcode() + ", expected " + argTypes[i].getOpcode(ILOAD);
							
							newInstructions.add(new FieldInsnNode(GETFIELD, acn.name, argName, argDesc));
						} else {
							assert (argTypes[i].getOpcode(ISTORE) == vin.getOpcode()) :
								"Wrong opcode " + vin.getOpcode() + ", expected " + argTypes[i].getOpcode(ISTORE);
							
							newInstructions.add(new InsnNode(SWAP));
							newInstructions.add(new FieldInsnNode(PUTFIELD, acn.name, argName, argDesc));
						}
						continue;
					}
					// decrease local variable indexes
					else {
						newInstructions.add(new VarInsnNode(vin.getOpcode(), vin.var - argTypes.length));
						continue;
					}
				}
			} else if (instruction instanceof FieldInsnNode) {
				final FieldInsnNode fin = (FieldInsnNode)instruction;
				MethodNode accessMethod;
				if ((fin.getOpcode() == GETSTATIC || fin.getOpcode() == GETFIELD) && (accessMethod = getAccessMethod(fin.owner, fin.name, fin.desc, "G")) != null) {
					newInstructions.add(new MethodInsnNode(INVOKESTATIC, classNode.name, accessMethod.name, accessMethod.desc, false));
					continue;
				};
				if ((fin.getOpcode() == PUTSTATIC || fin.getOpcode() == PUTFIELD) && (accessMethod = getAccessMethod(fin.owner, fin.name, fin.desc, "S")) != null) {
					newInstructions.add(new MethodInsnNode(INVOKESTATIC, classNode.name, accessMethod.name, accessMethod.desc, false));
					continue;
				};

			} else if (instruction instanceof MethodInsnNode) {
				// instance method call -> outer class instance method call using a generated access method
				final MethodInsnNode min = (MethodInsnNode) instruction;
				final MethodNode accessMethod;
				
				if ((min.getOpcode() == INVOKEVIRTUAL  || 
					 min.getOpcode() == INVOKESPECIAL ||
					 min.getOpcode() == INVOKESTATIC)
					 && (accessMethod = getAccessMethod(min.owner, min.name, min.desc, "M")) != null) {
					log.debug("Found " + BytecodeTraceUtil.toString(min));
					newInstructions.add(new MethodInsnNode(INVOKESTATIC, classNode.name, accessMethod.name, accessMethod.desc, false));

					continue;

				} else if (
					min.getOpcode() == INVOKESTATIC && 
					"asyncResult".equals(min.name) && 
					ASYNC_CALL_NAME.equals(min.owner)) {
					final VarInsnNode loadThis = new VarInsnNode(ALOAD, 0);
					newInstructions.add(loadThis);
					final FieldInsnNode loadFutureField = new FieldInsnNode(GETFIELD, acn.name, "future",  Type.getDescriptor(CompletionStage.class));
					newInstructions.add(loadFutureField);
					final MethodInsnNode setResultMethodCall = new MethodInsnNode(INVOKESTATIC, acn.name, "$result", "(Ljava/lang/Object;" + COMPLETION_STAGE_DESCRIPTOR + ")V", false);
					newInstructions.add(setResultMethodCall);
					
					continue;
				}
			} else if (instruction.getOpcode() == ARETURN) {
				newInstructions.add(new JumpInsnNode(GOTO, methodEnd));
				continue;
			}
			
			
			// do not make changes
			newInstructions.add(instruction);
		}
		///*
		newInstructions.add(beforeExceptionHandler);
		newInstructions.add(insideExceptionHandler);

		newInstructions.add(new FrameNode(F_FULL, 1, new Object[] {acn.name}, 1, new Object[] {"java/lang/Throwable"}));

		newInstructions.add(new VarInsnNode(ASTORE, 1));
		newInstructions.add(new VarInsnNode(ALOAD, 1));
		newInstructions.add(new VarInsnNode(ALOAD, 0));
		newInstructions.add(new FieldInsnNode(GETFIELD, acn.name, "future", Type.getDescriptor(CompletionStage.class)));
		newInstructions.add(new MethodInsnNode(INVOKESTATIC, acn.name, "$fault", "(Ljava/lang/Throwable;" + COMPLETION_STAGE_DESCRIPTOR + ")V", false));
		//*/
		newInstructions.add(methodEnd);
		
		newInstructions.add(new FrameNode(F_SAME, 0, null, 0, null));
		newInstructions.add(new InsnNode(RETURN));
		
		amn.instructions = newInstructions;
		// Maxs
		amn.maxLocals = methodNode.maxLocals - argTypes.length + 1; // +1 for exception
		amn.maxStack = Math.max(methodNode.maxStack, 2);
		
		return amn;
	}
	
	protected MethodNode createReplacementAsyncMethod(final ClassNode classNode, final MethodNode originalAsyncMethodNode, final String asyncTaskClassName) {
		final Type[] originalArgTypes = Type.getArgumentTypes(originalAsyncMethodNode.desc);
		
		final MethodNode replacementAsyncMethodNode = new MethodNode(originalAsyncMethodNode.access, originalAsyncMethodNode.name, originalAsyncMethodNode.desc, null, null);
		replacementAsyncMethodNode.visitAnnotation(CONTINUABLE_ANNOTATION_DESCRIPTOR, true);
		replacementAsyncMethodNode.visitCode();

		replacementAsyncMethodNode.visitTypeInsn(NEW, COMPLETABLE_FUTURE_NAME);
		replacementAsyncMethodNode.visitInsn(DUP);
		replacementAsyncMethodNode.visitMethodInsn(INVOKESPECIAL, COMPLETABLE_FUTURE_NAME, "<init>", "()V", false);
		replacementAsyncMethodNode.visitVarInsn(ASTORE, originalArgTypes.length + 1);
		
		replacementAsyncMethodNode.visitTypeInsn(NEW, asyncTaskClassName);
		replacementAsyncMethodNode.visitInsn(DUP);
		replacementAsyncMethodNode.visitVarInsn(ALOAD, 0);
		
		// CompletableFututre var
		replacementAsyncMethodNode.visitVarInsn(ALOAD, originalArgTypes.length + 1);
		
		// load all method arguments into stack
		for (int i = 0; i < originalArgTypes.length; i++) {
			replacementAsyncMethodNode.visitVarInsn(originalArgTypes[i].getOpcode(ILOAD), i + 1 );//Shifted for this
		}

		final Type[] constructorArgTypes = prependArray(
				originalArgTypes, 
				Type.getReturnType("L" + classNode.name + ";"),
				Type.getReturnType(COMPLETION_STAGE_DESCRIPTOR)
		);
		final String constructorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, constructorArgTypes);
		replacementAsyncMethodNode.visitMethodInsn(INVOKESPECIAL, asyncTaskClassName, "<init>", constructorDesc, false);
		
		replacementAsyncMethodNode.visitMethodInsn(INVOKESTATIC, ASYNC_EXECUTOR_NAME, "execute", "(Ljava/lang/Runnable;)V", false);
		replacementAsyncMethodNode.visitVarInsn(ALOAD, originalArgTypes.length + 1);
		replacementAsyncMethodNode.visitInsn(ARETURN);
		replacementAsyncMethodNode.visitMaxs(4 + originalArgTypes.length, 2 + originalArgTypes.length);

		replacementAsyncMethodNode.visitEnd();
		return replacementAsyncMethodNode;
	}
	
	protected void createAccessMethodsForAsyncMethod(final ClassNode classNode, final MethodNode methodNode) {
		final List<MethodNode> methods = methodsOf(classNode);
		for (final Iterator<?> i = methodNode.instructions.iterator(); i.hasNext(); ) {
			final AbstractInsnNode instruction = (AbstractInsnNode)i.next();
			if (instruction instanceof MethodInsnNode) {
				final MethodInsnNode methodInstructionNode = (MethodInsnNode) instruction;
				if ((methodInstructionNode.getOpcode() == INVOKEVIRTUAL  || 
					 methodInstructionNode.getOpcode() == INVOKESPECIAL ||
					 methodInstructionNode.getOpcode() == INVOKESTATIC)
					 && methodInstructionNode.owner.equals(classNode.name)) {
					final MethodNode targetMethodNode = getMethod(classNode, methodInstructionNode.name, methodInstructionNode.desc);
					if (null != targetMethodNode && (targetMethodNode.access & ACC_PRIVATE) != 0) {
						log.debug("Found " + BytecodeTraceUtil.toString(methodInstructionNode));
						methods.add(createAccessMethod(classNode, methodInstructionNode.name, methodInstructionNode.desc, (targetMethodNode.access & ACC_STATIC) != 0));
					}
				}
			}
			if (instruction instanceof FieldInsnNode) {
				final FieldInsnNode fieldInstructionNode = (FieldInsnNode) instruction;
				if (fieldInstructionNode.owner.equals(classNode.name)) {
					final FieldNode targetFieldNode = getField(classNode, fieldInstructionNode.name, fieldInstructionNode.desc);
					if (null != targetFieldNode && (targetFieldNode.access & ACC_PRIVATE) != 0) {
						//log.debug("Found " + BytecodeTraceUtil.toString(fieldInstructionNode));
						if (fieldInstructionNode.getOpcode() == GETSTATIC || fieldInstructionNode.getOpcode() == GETFIELD) {
							methods.add(createAccessGetter(classNode, fieldInstructionNode.name, fieldInstructionNode.desc, (targetFieldNode.access & ACC_STATIC) != 0));
						} else if (fieldInstructionNode.getOpcode() == PUTSTATIC || fieldInstructionNode.getOpcode() == PUTFIELD) {
							methods.add(createAccessSetter(classNode, fieldInstructionNode.name, fieldInstructionNode.desc, (targetFieldNode.access & ACC_STATIC) != 0));
						}
					}
				}
			}
		}
	}
	
	protected MethodNode createAccessMethod(final ClassNode classNode, final String methodName, final String methodDesc, final boolean isStatic) {
		final String name = createAccessMethodName(classNode);
		final Type[] originalArgTypes = Type.getArgumentTypes(methodDesc);
		final Type[] argTypes = isStatic ? originalArgTypes : prependArray(originalArgTypes, Type.getReturnType("L" + classNode.name + ";")); 
		final Type returnType = Type.getReturnType(methodDesc);
		final String desc = Type.getMethodDescriptor(returnType, argTypes);
		
		final MethodNode acessMethodNode = new MethodNode(ACC_STATIC + ACC_SYNTHETIC, name, desc, null, null);
		acessMethodNode.visitCode();
		
		// load all method arguments into stack
		final int arity = argTypes.length;
		for (int i = 0; i < arity; i++) {
			final int opcode = argTypes[i].getOpcode(ILOAD);
			log.debug("Using opcode " + opcode + " for loading " + argTypes[i]);
			acessMethodNode.visitVarInsn(opcode, i);
		}
		acessMethodNode.visitMethodInsn(isStatic ? INVOKESTATIC : INVOKESPECIAL, classNode.name, methodName, methodDesc, false);
		acessMethodNode.visitInsn(returnType.getOpcode(IRETURN));
		acessMethodNode.visitMaxs(argTypes.length, argTypes.length);
		acessMethodNode.visitEnd();
		
		// Register mapping
		registerAccessMethod(classNode.name, methodName, methodDesc, "M", acessMethodNode);
		
		return acessMethodNode;
	}
	
	protected MethodNode createAccessGetter(final ClassNode classNode, final String fieldName, final String fieldDesc, final boolean isStatic) {
		final String name = createAccessMethodName(classNode);
		final Type[] argTypes = isStatic ? new Type[0] : new Type[]{Type.getReturnType("L" + classNode.name + ";")}; 
		final Type returnType = Type.getReturnType(fieldDesc);
		final String desc = Type.getMethodDescriptor(returnType, argTypes);
		
		final MethodNode acessMethodNode = new MethodNode(ACC_STATIC + ACC_SYNTHETIC, name, desc, null, null);
		acessMethodNode.visitCode();
		
		// load all method arguments into stack
		final int arity = argTypes.length;
		for (int i = 0; i < arity; i++) {
			final int opcode = argTypes[i].getOpcode(ILOAD);
			log.debug("Using opcode " + opcode + " for loading " + argTypes[i]);
			acessMethodNode.visitVarInsn(opcode, i);
		}
		acessMethodNode.visitFieldInsn(isStatic ? GETSTATIC : GETFIELD, classNode.name, fieldName, fieldDesc);
		acessMethodNode.visitInsn(returnType.getOpcode(IRETURN));
		acessMethodNode.visitMaxs(1, argTypes.length);
		acessMethodNode.visitEnd();
		
		// Register mapping
		registerAccessMethod(classNode.name, fieldName, fieldDesc, "G", acessMethodNode);
		
		return acessMethodNode;
	}
	
	protected MethodNode createAccessSetter(final ClassNode classNode, final String fieldName, final String fieldDesc, final boolean isStatic) {
		final String name = createAccessMethodName(classNode);
		final Type[] argTypes = isStatic ? 
			new Type[]{Type.getReturnType(fieldDesc)} : 
			new Type[]{Type.getReturnType("L" + classNode.name + ";"), Type.getReturnType(fieldDesc)};
		final Type returnType = Type.getReturnType("V"); // <-- void
		final String desc = Type.getMethodDescriptor(returnType, argTypes);
		
		final MethodNode acessMethodNode = new MethodNode(ACC_STATIC + ACC_SYNTHETIC, name, desc, null, null);
		acessMethodNode.visitCode();
		
		// load all method arguments into stack
		final int arity = argTypes.length;
		for (int i = 0; i < arity; i++) {
			final int opcode = argTypes[i].getOpcode(ILOAD);
			log.debug("Using opcode " + opcode + " for loading " + argTypes[i]);
			acessMethodNode.visitVarInsn(opcode, i);
		}
		acessMethodNode.visitFieldInsn(isStatic ? PUTSTATIC : PUTFIELD, classNode.name, fieldName, fieldDesc);
		acessMethodNode.visitInsn(RETURN);
		acessMethodNode.visitMaxs(argTypes.length, argTypes.length);
		acessMethodNode.visitEnd();
		
		// Register mapping
		registerAccessMethod(classNode.name, fieldName, fieldDesc, "S", acessMethodNode);
		
		return acessMethodNode;
	}
	
	
	// --- Removing @async annotation
	
	private static void removeAsyncAnnotation(final MethodNode methodNode) {
		if (methodNode.invisibleAnnotations != null) {
			for (final Iterator<AnnotationNode> it = invisibleAnnotationsOf(methodNode).iterator(); it.hasNext();) {
				final AnnotationNode an = it.next();
				if (ASYNC_ANNOTATION_DESCRIPTOR.equals(an.desc)) {
					it.remove();
					log.debug("@async annotation removed, method: " + methodNode);
					return;
				}
			}
		}
		throw new IllegalStateException("No @async annotation found to remove");
	}
	
	// --- Instructions and Opcodes ---
	
	private static boolean isLoadOpcode(final int opcode) {
		return opcode >= ILOAD && opcode < ISTORE;
	}
	
	// --- Creating names ---
	
	private static String createInnerClassName(final ClassNode classNode) {
		int index = 1;
		String name;
		while (hasInnerClass(classNode, name = createInnerClassName(classNode, index))) {
			index++;
		}
		log.debug("Generated new inner class name: " + name);
		return name;
	}
	
	private static String createInnerClassName(final ClassNode classNode, final int index) {
		return classNode.name + "$" + index;
	}
	
	private static String createAccessMethodName(final ClassNode classNode) {
		int index = 0;
		String name;
		while (hasMethod(classNode, name = createAccessMethodName(index))) {
			index++;
		}
		log.trace("Generated new method name: " + name);
		return name;
	}
	
	private static String createAccessMethodName(final int index) {
		return "access$" + index;
	}
	
	private static String createOuterClassMethodArgFieldName(final int index) {
		return "val$" + index;
	}
	
	// --- Finding inner classes ---
	
	private static boolean hasInnerClass(final ClassNode classNode, final String innerClassName) {
		return getInnerClass(classNode, innerClassName) != null;
	}
	
	private static InnerClassNode getInnerClass(final ClassNode classNode, final String innerClassName) {
		for (final InnerClassNode icn : innerClassesOf(classNode)) {
			if (innerClassName.equals(icn.name)) {
				return icn;
			}
		}
		return null;
	}
	
	// --- Finding methods ---
	
	private static boolean hasMethod(final ClassNode classNode, final String methodName) {
		return getMethod(classNode, methodName, null) != null;
	}
	
	private static MethodNode getMethod(final ClassNode classNode, final String methodName, final String methodDesc) {
		for (final MethodNode methodNode : methodsOf(classNode)) {
			if (methodName.equals(methodNode.name) && (methodDesc == null || methodDesc.equals(methodNode.desc))) {
				return methodNode;
			}
		}
		return null;
	}
	
	private static FieldNode getField(final ClassNode classNode, final String fieldName, final String fieldDesc) {
		for (final FieldNode fieldNode : fieldsOf(classNode)) {
			if (fieldName.equals(fieldNode.name) && (fieldDesc == null || fieldDesc.equals(fieldNode.desc))) {
				return fieldNode;
			}
		}
		return null;
	}
	
	// --- Detecting blocking method ---

	private static boolean isAsyncMethod(final MethodNode methodNode) {
		if (hasAsyncAnnotation(methodNode)) {
			return true;
		}
		return false;
	}
	
	private static boolean hasAsyncAnnotation(final MethodNode methodNode) {
		final boolean found = annotationPresent(invisibleAnnotationsOf(methodNode), ASYNC_ANNOTATION_DESCRIPTOR) || annotationPresent(visibleAnnotationsOf(methodNode), ASYNC_ANNOTATION_DESCRIPTOR);
		if (found) {
			log.debug("@Async annotation found, method: " + methodNode);
		}
		return found;
	}
	
	private static boolean annotationPresent(final List<AnnotationNode> annotations, final String targetAnnotationTypeDescriptor) {
		for (final AnnotationNode annotation : annotations) {
			if (targetAnnotationTypeDescriptor.equals(annotation.desc)) {
				return true;
			}
		}
		return false;
	}
	
	@SuppressWarnings("unchecked")
	private static List<MethodNode> methodsOf(final ClassNode classNode) {
		return null == classNode.methods ? Collections.<MethodNode>emptyList() : (List<MethodNode>)classNode.methods;
	}
	
	@SuppressWarnings("unchecked")
	private static List<FieldNode> fieldsOf(final ClassNode classNode) {
		return null == classNode.fields ? Collections.<FieldNode>emptyList() : (List<FieldNode>)classNode.fields;
	}
	
	@SuppressWarnings("unchecked")
	private static List<InnerClassNode> innerClassesOf(final ClassNode classNode) {
		return null == classNode.innerClasses ? Collections.<InnerClassNode>emptyList() : (List<InnerClassNode>)classNode.innerClasses;
	}
	
	private static List<AnnotationNode> visibleAnnotationsOf(final MethodNode methodNode) {
		return safeAnnotationsList(methodNode.visibleAnnotations);
	}
	
	private static List<AnnotationNode> invisibleAnnotationsOf(final MethodNode methodNode) {
		return safeAnnotationsList(methodNode.invisibleAnnotations);
	}

	
	@SuppressWarnings("unchecked")
	private static List<AnnotationNode> safeAnnotationsList(final List<?> annotations) {
		return null == annotations ? Collections.<AnnotationNode>emptyList() : (List<AnnotationNode>)annotations;
	}
	
	private static Type[] prependArray(final Type[] array, final Type value) {
		final Type[] result = new Type[array.length + 1];
		result[0] = value;
		System.arraycopy(array, 0, result, 1, array.length);
		return result;
	}
	
	private static Type[] prependArray(final Type[] array, final Type value1, final Type value2) {
		final Type[] result = new Type[array.length + 2];
		result[0] = value1;
		result[1] = value2;
		System.arraycopy(array, 0, result, 2, array.length);
		return result;
	}

 }
