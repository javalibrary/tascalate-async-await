package net.tascalate.async.tools.core;

import static net.tascalate.async.tools.core.BytecodeIntrospection.createAccessMethodName;
import static net.tascalate.async.tools.core.BytecodeIntrospection.createInnerClassName;
import static net.tascalate.async.tools.core.BytecodeIntrospection.getField;
import static net.tascalate.async.tools.core.BytecodeIntrospection.getMethod;
import static net.tascalate.async.tools.core.BytecodeIntrospection.innerClassesOf;
import static net.tascalate.async.tools.core.BytecodeIntrospection.methodsOf;
import static net.tascalate.async.tools.core.BytecodeIntrospection.removeAsyncAnnotation;
import static org.objectweb.asm.Opcodes.*;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

abstract public class AbstractMethodTransformer {
    protected final static Log log = LogFactory.getLog(AsyncAwaitClassFileGenerator.class);
    
    protected final static String ASYNC_CALL_NAME = "net/tascalate/async/api/AsyncCall";
    protected final static String ASYNC_EXECUTOR_NAME = "net/tascalate/async/core/AsyncExecutor";
    protected final static String CONTINUABLE_ANNOTATION_DESCRIPTOR = "Lorg/apache/commons/javaflow/api/continuable;";

    protected final ClassNode classNode;
    protected final List<InnerClassNode> originalInnerClasses;
    protected final MethodNode originalAsyncMethod;
    
    // New generated classes
    protected final List<ClassNode> newClasses;

    // Original method's "method name + method desc" -> Access method's
    // MethodNode
    protected final Map<String, MethodNode> accessMethods;
    
    protected AbstractMethodTransformer(ClassNode               classNode, 
                                        List<InnerClassNode>    originalInnerClasses,
                                        MethodNode              originalAsyncMethod,
                                        List<ClassNode>         newClasses, 
                                        Map<String, MethodNode> accessMethods) {
        
        this.classNode = classNode;
        this.originalInnerClasses = originalInnerClasses;
        this.originalAsyncMethod = originalAsyncMethod;
        this.newClasses = newClasses;
        this.accessMethods = accessMethods;
    }

    
    public void transform() {
        log.info("Transforming blocking method: " + classNode.name + "." + originalAsyncMethod.name
                + originalAsyncMethod.desc);
        // Remove @async annotation
        removeAsyncAnnotation(originalAsyncMethod);

        // Create InnerClassNode for anoymous class
        String asyncTaskClassName = createInnerClassName(classNode);
        innerClassesOf(classNode).add(new InnerClassNode(asyncTaskClassName, null, null, 0));

        // Create accessor methods
        createAccessMethodsForAsyncMethod();

        // Create ClassNode for anonymous class
        ClassNode asyncTaskClassNode = createAnonymousClass(asyncTaskClassName);
        newClasses.add(asyncTaskClassNode);

        // Replace original method

        MethodNode replacementAsyncMethodNode = createReplacementAsyncMethod(asyncTaskClassName);
        
        List<MethodNode> methods = methodsOf(classNode);
        methods.set(methods.indexOf(originalAsyncMethod), replacementAsyncMethodNode);        
    }
    
    abstract protected ClassNode createAnonymousClass(String asyncTaskClassName);
    abstract protected MethodNode createReplacementAsyncMethod(String asyncTaskClassName);
    
    protected void createAccessMethodsForAsyncMethod() {
        List<MethodNode> methods = methodsOf(classNode);
        for (Iterator<?> i = originalAsyncMethod.instructions.iterator(); i.hasNext();) {
            AbstractInsnNode instruction = (AbstractInsnNode) i.next();
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode methodInstructionNode = (MethodInsnNode) instruction;
                if ((methodInstructionNode.getOpcode() == INVOKEVIRTUAL || 
                     methodInstructionNode.getOpcode() == INVOKESPECIAL || 
                     methodInstructionNode.getOpcode() == INVOKESTATIC
                    ) && methodInstructionNode.owner.equals(classNode.name)) {
                    
                    MethodNode targetMethodNode = getMethod(methodInstructionNode.name, methodInstructionNode.desc,
                            methods);
                    if (null != targetMethodNode && (targetMethodNode.access & ACC_PRIVATE) != 0) {
                        log.debug("Found private call " + BytecodeTraceUtil.toString(methodInstructionNode));
                        createAccessMethod(methodInstructionNode,
                                (targetMethodNode.access & ACC_STATIC) != 0, methods);
                    }
                }

                if (methodInstructionNode.getOpcode() == INVOKESPECIAL && 
                    !"<init>".equals(methodInstructionNode.name)  && 
                    !methodInstructionNode.owner.equals(classNode.name)) {
                    // INVOKESPECIAL is used for constructors/super-call,
                    // private instance methods
                    // Here we filtered out only to private super-method calls
                    log.debug("Found super-call " + BytecodeTraceUtil.toString(methodInstructionNode));
                    createAccessMethod(methodInstructionNode, false, methods);
                }

            }
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode fieldInstructionNode = (FieldInsnNode) instruction;
                if (fieldInstructionNode.owner.equals(classNode.name)) {
                    FieldNode targetFieldNode = 
                        getField(classNode, fieldInstructionNode.name, fieldInstructionNode.desc);
                    
                    if (null != targetFieldNode && (targetFieldNode.access & ACC_PRIVATE) != 0) {
                        // log.debug("Found " +
                        // BytecodeTraceUtil.toString(fieldInstructionNode));
                        if (fieldInstructionNode.getOpcode() == GETSTATIC || 
                            fieldInstructionNode.getOpcode() == GETFIELD) {
                            
                            createAccessGetter(fieldInstructionNode, (targetFieldNode.access & ACC_STATIC) != 0, methods);
                            
                        } else if (fieldInstructionNode.getOpcode() == PUTSTATIC || 
                                   fieldInstructionNode.getOpcode() == PUTFIELD) {
                            
                            createAccessSetter(fieldInstructionNode, (targetFieldNode.access & ACC_STATIC) != 0, methods);
                        }
                    }
                }
            }
        }
    }

    protected MethodNode createAccessMethod(MethodInsnNode   targetMethodNode, 
                                            boolean          isStatic,
                                            List<MethodNode> methods) {
        
        MethodNode accessMethodNode = 
            getAccessMethod(targetMethodNode.owner, targetMethodNode.name, targetMethodNode.desc, "M");
        
        if (null != accessMethodNode) {
            return accessMethodNode;
        }

        String name = createAccessMethodName(methods);
        Type[] originalArgTypes = Type.getArgumentTypes(targetMethodNode.desc);
        Type[] argTypes = isStatic ? originalArgTypes : prependArray(originalArgTypes, Type.getObjectType(classNode.name));
        Type returnType = Type.getReturnType(targetMethodNode.desc);
        String desc = Type.getMethodDescriptor(returnType, argTypes);

        accessMethodNode = new MethodNode(ACC_STATIC + ACC_SYNTHETIC, name, desc, null, null);
        accessMethodNode.visitCode();

        // load all method arguments into stack
        int arity = argTypes.length;
        for (int i = 0; i < arity; i++) {
            int opcode = argTypes[i].getOpcode(ILOAD);
            log.debug("Using opcode " + opcode + " for loading " + argTypes[i]);
            accessMethodNode.visitVarInsn(opcode, i);
        }
        accessMethodNode.visitMethodInsn(
            isStatic ? INVOKESTATIC : INVOKESPECIAL, targetMethodNode.owner, targetMethodNode.name, targetMethodNode.desc, targetMethodNode.itf
        );
        accessMethodNode.visitInsn(returnType.getOpcode(IRETURN));
        accessMethodNode.visitMaxs(argTypes.length, argTypes.length);
        accessMethodNode.visitEnd();

        // Register mapping
        registerAccessMethod(targetMethodNode.owner, targetMethodNode.name, targetMethodNode.desc, "M", accessMethodNode);
        methods.add(accessMethodNode);
        return accessMethodNode;
    }

    protected MethodNode createAccessGetter(FieldInsnNode    targetFieldNode, 
                                            boolean          isStatic,
                                            List<MethodNode> methods) {
        
        MethodNode accessMethodNode = 
            getAccessMethod(targetFieldNode.owner, targetFieldNode.name, targetFieldNode.desc, "G");
        
        if (null != accessMethodNode) {
            return accessMethodNode;
        }

        String name = createAccessMethodName(methods);
        Type[] argTypes = isStatic ? new Type[0] : new Type[] { Type.getObjectType(classNode.name) };
        Type returnType = Type.getType(targetFieldNode.desc);
        String desc = Type.getMethodDescriptor(returnType, argTypes);

        accessMethodNode = new MethodNode(ACC_STATIC + ACC_SYNTHETIC, name, desc, null, null);
        accessMethodNode.visitCode();

        // load all method arguments into stack
        int arity = argTypes.length;
        for (int i = 0; i < arity; i++) {
            int opcode = argTypes[i].getOpcode(ILOAD);
            log.debug("Using opcode " + opcode + " for loading " + argTypes[i]);
            accessMethodNode.visitVarInsn(opcode, i);
        }
        accessMethodNode.visitFieldInsn(
            isStatic ? GETSTATIC : GETFIELD, targetFieldNode.owner, targetFieldNode.name, targetFieldNode.desc
        );
        accessMethodNode.visitInsn(returnType.getOpcode(IRETURN));
        accessMethodNode.visitMaxs(1, argTypes.length);
        accessMethodNode.visitEnd();

        // Register mapping
        registerAccessMethod(targetFieldNode.owner, targetFieldNode.name, targetFieldNode.desc, "G", accessMethodNode);
        methods.add(accessMethodNode);
        return accessMethodNode;
    }

    protected MethodNode createAccessSetter(FieldInsnNode    targetFieldNode, 
                                            boolean          isStatic,
                                            List<MethodNode> methods) {
        
        MethodNode accessMethodNode = 
            getAccessMethod(targetFieldNode.owner, targetFieldNode.name, targetFieldNode.desc, "S");
        
        if (null != accessMethodNode) {
            return accessMethodNode;
        }

        String name = createAccessMethodName(methods);
        Type[] argTypes = isStatic ? 
                new Type[] { Type.getType(targetFieldNode.desc) } : 
                new Type[] { Type.getObjectType(classNode.name), Type.getType(targetFieldNode.desc) };                
                
        Type returnType = Type.VOID_TYPE;
        String desc = Type.getMethodDescriptor(returnType, argTypes);

        accessMethodNode = new MethodNode(ACC_STATIC + ACC_SYNTHETIC, name, desc, null, null);
        accessMethodNode.visitCode();

        // load all method arguments into stack
        int arity = argTypes.length;
        for (int i = 0; i < arity; i++) {
            int opcode = argTypes[i].getOpcode(ILOAD);
            log.debug("Using opcode " + opcode + " for loading " + argTypes[i]);
            accessMethodNode.visitVarInsn(opcode, i);
        }
        accessMethodNode.visitFieldInsn(
            isStatic ? PUTSTATIC : PUTFIELD, targetFieldNode.owner, targetFieldNode.name, targetFieldNode.desc
        );
        accessMethodNode.visitInsn(RETURN);
        accessMethodNode.visitMaxs(argTypes.length, argTypes.length);
        accessMethodNode.visitEnd();

        // Register mapping
        registerAccessMethod(targetFieldNode.owner, targetFieldNode.name, targetFieldNode.desc, "S", accessMethodNode);
        methods.add(accessMethodNode);
        return accessMethodNode;
    }

    private void registerAccessMethod(String owner, String name, String desc, String kind, MethodNode methodNode) {
        accessMethods.put(owner + name + desc + "-" + kind, methodNode);
    }

    protected MethodNode getAccessMethod(String owner, String name, String desc, String kind) {
        return accessMethods.get(owner + name + desc + "-" + kind);
    }

    
    protected static Type[] prependArray(Type[] array, Type value) {
        Type[] result = new Type[array.length + 1];
        result[0] = value;
        System.arraycopy(array, 0, result, 1, array.length);
        return result;
    }
    
}