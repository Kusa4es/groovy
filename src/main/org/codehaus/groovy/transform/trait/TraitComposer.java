/*
 * Copyright 2003-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.transform.trait;

import groovy.transform.CompileStatic;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.ExceptionUtils;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public abstract class TraitComposer {
    /**
     * This comparator is used to make sure that generated direct getters appear first in the list of method
     * nodes.
     */
    private static final Comparator<MethodNode> GETTER_FIRST_COMPARATOR = new Comparator<MethodNode>() {
        public int compare(final MethodNode o1, final MethodNode o2) {
            if (o1.getName().endsWith(TraitConstants.DIRECT_GETTER_SUFFIX)) return -1;
            return 1;
        }
    };

    public static void doExtendTraits(final ClassNode cNode, final SourceUnit unit) {
        boolean isItselfTrait = isTrait(cNode);
        if (isItselfTrait) {
            inheritTrait(cNode, unit);
        }
        ClassNode[] interfaces = cNode.getInterfaces();
        for (ClassNode trait : interfaces) {
            List<AnnotationNode> traitAnn = trait.getAnnotations(TraitConstants.TRAIT_CLASSNODE);
            if (traitAnn != null && !traitAnn.isEmpty() && !cNode.getNameWithoutPackage().endsWith(TraitConstants.TRAIT_HELPER)) {
                Iterator<InnerClassNode> innerClasses = trait.redirect().getInnerClasses();
                if (innerClasses != null && innerClasses.hasNext()) {
                    // trait defined in same source unit
                    ClassNode helperClassNode = null;
                    ClassNode fieldHelperClassNode = null;
                    while (innerClasses.hasNext()) {
                        ClassNode icn = innerClasses.next();
                        if (icn.getName().endsWith(TraitConstants.FIELD_HELPER)) {
                            fieldHelperClassNode = icn;
                        } else if (icn.getName().endsWith(TraitConstants.TRAIT_HELPER)) {
                            helperClassNode = icn;
                        }
                    }
                    applyTrait(trait, cNode, helperClassNode, fieldHelperClassNode);
                } else {
                    applyPrecompiledTrait(trait, cNode);
                }
            }
        }
    }

    private static boolean isTrait(final ClassNode cNode) {
        return cNode.isInterface() && !cNode.getAnnotations(TraitConstants.TRAIT_CLASSNODE).isEmpty();
    }

    private static void inheritTrait(final ClassNode bottomTrait, final SourceUnit unit) {
        ClassNode superClass = bottomTrait.getSuperClass();
        if (superClass==null || ClassHelper.OBJECT_TYPE.equals(superClass)) return;
        if (!isTrait(superClass)) {
            unit.addError(new SyntaxException("A trait can only inherit from another trait", superClass.getLineNumber(), superClass.getColumnNumber()));
            return;
        }
        //System.out.println("bottomTrait = " + bottomTrait);
    }

    private static void applyPrecompiledTrait(final ClassNode trait, final ClassNode cNode) {
        try {
            final ClassLoader classLoader = trait.getTypeClass().getClassLoader();
            String helperClassName = TraitConstants.helperClassName(trait);
            final ClassNode helperClassNode = ClassHelper.make(classLoader.loadClass(helperClassName));
            ClassNode fieldHelperClassNode;
            try {
                fieldHelperClassNode = ClassHelper.make(classLoader.loadClass(TraitConstants.fieldHelperClassName(trait)));
            } catch (ClassNotFoundException e) {
                fieldHelperClassNode = null;
            }

            applyTrait(trait, cNode, helperClassNode, fieldHelperClassNode);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private static void applyTrait(final ClassNode trait, final ClassNode cNode, final ClassNode helperClassNode, final ClassNode fieldHelperClassNode) {
        for (MethodNode methodNode : helperClassNode.getAllDeclaredMethods()) {
            String name = methodNode.getName();
            int access = methodNode.getModifiers();
            Parameter[] argumentTypes = methodNode.getParameters();
            ClassNode[] exceptions = methodNode.getExceptions();
            ClassNode returnType = methodNode.getReturnType();
            boolean isAbstract = methodNode.isAbstract();
            if (!isAbstract && argumentTypes.length > 0 && ((access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) && !name.contains("$")) {
                ArgumentListExpression argList = new ArgumentListExpression();
                argList.addExpression(new VariableExpression("this"));
                Parameter[] params = new Parameter[argumentTypes.length - 1];
                for (int i = 1; i < argumentTypes.length; i++) {
                    Parameter parameter = argumentTypes[i];
                    params[i - 1] = new Parameter(parameter.getOriginType(), "arg" + i);
                    argList.addExpression(new VariableExpression(params[i - 1]));
                }
                MethodNode existingMethod = cNode.getDeclaredMethod(name, params);
                if (existingMethod != null || isExistingProperty(name, cNode, params)) {
                    // override exists in the weaved class
                    continue;
                }
                ClassNode[] exceptionNodes = new ClassNode[exceptions == null ? 0 : exceptions.length];
                System.arraycopy(exceptions, 0, exceptionNodes, 0, exceptionNodes.length);
                MethodCallExpression mce = new MethodCallExpression(
                        new ClassExpression(helperClassNode),
                        name,
                        argList
                );
                mce.setImplicitThis(false);
                MethodNode forwarder = new MethodNode(
                        name,
                        access ^ Opcodes.ACC_STATIC,
                        returnType,
                        params,
                        exceptionNodes,
                        new ExpressionStatement(mce)
                );
                cNode.addMethod(forwarder);
            }
        }
        cNode.addObjectInitializerStatements(new ExpressionStatement(
                new MethodCallExpression(
                        new ClassExpression(helperClassNode),
                        TraitConstants.STATIC_INIT_METHOD,
                        new ArgumentListExpression(new VariableExpression("this")))
        ));
        if (fieldHelperClassNode != null) {
            // we should implement the field helper interface too
            cNode.addInterface(fieldHelperClassNode);
            // implementation of methods
            List<MethodNode> declaredMethods = fieldHelperClassNode.getAllDeclaredMethods();
            Collections.sort(declaredMethods, GETTER_FIRST_COMPARATOR);
            for (MethodNode methodNode : declaredMethods) {
                String fieldName = methodNode.getName();
                if (fieldName.endsWith(TraitConstants.DIRECT_GETTER_SUFFIX) || fieldName.endsWith(TraitConstants.DIRECT_SETTER_SUFFIX)) {
                    int suffixIdx = fieldName.lastIndexOf("$");
                    fieldName = fieldName.substring(0, suffixIdx);
                    String operation = methodNode.getName().substring(suffixIdx + 1);
                    boolean getter = "get".equals(operation);
                    if (getter) {
                        // add field
                        cNode.addField(TraitConstants.remappedFieldName(trait, fieldName), Opcodes.ACC_PRIVATE, methodNode.getReturnType(), null);
                    }
                    Parameter[] newParams = getter ? Parameter.EMPTY_ARRAY :
                            new Parameter[]{new Parameter(methodNode.getParameters()[0].getOriginType(), "val")};
                    Expression fieldExpr = new VariableExpression(cNode.getField(TraitConstants.remappedFieldName(trait, fieldName)));
                    Statement body =
                            getter ? new ReturnStatement(fieldExpr) :
                                    new ExpressionStatement(
                                            new BinaryExpression(
                                                    fieldExpr,
                                                    Token.newSymbol(Types.EQUAL, 0, 0),
                                                    new VariableExpression(newParams[0])
                                            )
                                    );
                    MethodNode impl = new MethodNode(
                            methodNode.getName(),
                            Opcodes.ACC_PUBLIC,
                            methodNode.getReturnType(),
                            newParams,
                            ClassNode.EMPTY_ARRAY,
                            body
                    );
                    impl.addAnnotation(new AnnotationNode(ClassHelper.make(CompileStatic.class)));
                    cNode.addMethod(impl);
                }
            }
        }
    }

    private static boolean isExistingProperty(final String methodName, final ClassNode cNode, final Parameter[] params) {
        String propertyName = methodName;
        boolean getter = false;
        if (methodName.startsWith("get")) {
            propertyName = propertyName.substring(3);
            getter = true;
        } else if (methodName.startsWith("is")) {
            propertyName = propertyName.substring(2);
            getter = true;
        } else if (methodName.startsWith("set")) {
            propertyName = propertyName.substring(3);
        } else {
            return false;
        }
        if (getter && params.length>0) {
            return false;
        }
        if (!getter && params.length!=1) {
            return false;
        }
        propertyName = MetaClassHelper.convertPropertyName(propertyName);
        PropertyNode pNode = cNode.getProperty(propertyName);
        return pNode != null;
    }
}
