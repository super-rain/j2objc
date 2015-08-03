/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.translate;

import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.ArrayAccess;
import com.google.devtools.j2objc.ast.ArrayCreation;
import com.google.devtools.j2objc.ast.ArrayInitializer;
import com.google.devtools.j2objc.ast.Assignment;
import com.google.devtools.j2objc.ast.CreationReference;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.ExpressionMethodReference;
import com.google.devtools.j2objc.ast.FieldAccess;
import com.google.devtools.j2objc.ast.FunctionInvocation;
import com.google.devtools.j2objc.ast.InstanceofExpression;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.MethodReference;
import com.google.devtools.j2objc.ast.NumberLiteral;
import com.google.devtools.j2objc.ast.PrefixExpression;
import com.google.devtools.j2objc.ast.QualifiedName;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.SuperMethodReference;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.TypeLiteral;
import com.google.devtools.j2objc.ast.TypeMethodReference;
import com.google.devtools.j2objc.types.GeneratedTypeBinding;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.types.IOSMethodBinding;
import com.google.devtools.j2objc.types.IOSTypeBinding;
import com.google.devtools.j2objc.util.TranslationUtil;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites array creation into a method invocation on an IOSArray class.
 *
 * @author Keith Stanger
 */
public class ArrayRewriter extends TreeVisitor {

  @Override
  public void endVisit(ArrayCreation node) {
    node.replaceWith(createInvocation(node));
  }

  private MethodInvocation createInvocation(ArrayCreation node) {
    ITypeBinding arrayType = node.getTypeBinding();
    assert arrayType.isArray();
    boolean retainedResult = node.hasRetainedResult() || Options.useARC();
    ArrayInitializer initializer = node.getInitializer();
    if (initializer != null) {
      return newInitializedArrayInvocation(arrayType, initializer.getExpressions(), retainedResult);
    } else {
      List<Expression> dimensions = node.getDimensions();
      if (dimensions.size() == 1) {
        return newSingleDimensionArrayInvocation(arrayType, dimensions.get(0), retainedResult);
      } else {
        return newMultiDimensionArrayInvocation(arrayType, dimensions, retainedResult);
      }
    }
  }

  private MethodInvocation newInitializedArrayInvocation(
      ITypeBinding arrayType, List<Expression> elements, boolean retainedResult) {
    ITypeBinding componentType = arrayType.getComponentType();
    IOSTypeBinding iosArrayBinding = typeEnv.resolveArrayType(componentType);

    IOSMethodBinding methodBinding = IOSMethodBinding.newMethod(
        getInitializeSelector(componentType, retainedResult), Modifier.PUBLIC | Modifier.STATIC,
        iosArrayBinding, iosArrayBinding);
    methodBinding.addParameter(iosArrayBinding);
    methodBinding.addParameter(typeEnv.resolveJavaType("int"));
    if (!componentType.isPrimitive()) {
      methodBinding.addParameter(typeEnv.getIOSClass());
    }
    MethodInvocation invocation =
        new MethodInvocation(methodBinding, new SimpleName(iosArrayBinding));

    // Create the array initializer and add it as the first parameter.
    ArrayInitializer arrayInit = new ArrayInitializer(arrayType);
    for (Expression element : elements) {
      arrayInit.getExpressions().add(element.copy());
    }
    invocation.getArguments().add(arrayInit);

    // Add the array size parameter.
    invocation.getArguments().add(
        NumberLiteral.newIntLiteral(arrayInit.getExpressions().size(), typeEnv));

    // Add the type argument for object arrays.
    if (!componentType.isPrimitive()) {
      invocation.getArguments().add(newTypeLiteral(componentType));
    }

    return invocation;
  }

  private String paramNameForPrimitive(char binaryName) {
    switch (binaryName) {
      case 'B': return "Bytes";
      case 'C': return "Chars";
      case 'D': return "Doubles";
      case 'F': return "Floats";
      case 'I': return "Ints";
      case 'J': return "Longs";
      case 'S': return "Shorts";
      case 'Z': return "Booleans";
    }
    throw new AssertionError("Unknown primitive type: " + binaryName);
  }

  private String getInitializeSelector(ITypeBinding componentType, boolean retainedResult) {
    String selectorFmt = "arrayWith%s:count:";
    if (retainedResult) {
      selectorFmt = "newArrayWith%s:count:";
    }
    String paramName;
    if (componentType.isPrimitive()) {
      paramName = paramNameForPrimitive(componentType.getBinaryName().charAt(0));
    } else {
      paramName = "Objects";
      selectorFmt += "type:";
    }
    return String.format(selectorFmt, paramName);
  }

  private MethodInvocation newSingleDimensionArrayInvocation(
      ITypeBinding arrayType, Expression dimensionExpr, boolean retainedResult) {
    ITypeBinding componentType = arrayType.getComponentType();
    IOSTypeBinding iosArrayBinding = typeEnv.resolveArrayType(componentType);

    String selector = (retainedResult ? "newArray" : "array") + "WithLength:"
        + (componentType.isPrimitive() ? "" : "type:");
    IOSMethodBinding methodBinding = IOSMethodBinding.newMethod(
        selector, Modifier.PUBLIC | Modifier.STATIC, iosArrayBinding, iosArrayBinding);
    methodBinding.addParameter(typeEnv.resolveJavaType("int"));
    if (!componentType.isPrimitive()) {
      methodBinding.addParameter(typeEnv.getIOSClass());
    }
    MethodInvocation invocation =
        new MethodInvocation(methodBinding, new SimpleName(iosArrayBinding));

    // Add the array length argument.
    invocation.getArguments().add(dimensionExpr.copy());

    // Add the type argument for object arrays.
    if (!componentType.isPrimitive()) {
      invocation.getArguments().add(newTypeLiteral(componentType));
    }

    return invocation;
  }

  private MethodInvocation newMultiDimensionArrayInvocation(
      ITypeBinding componentType, List<Expression> dimensions, boolean retainedResult) {
    assert dimensions.size() > 1;
    for (int i = 0; i < dimensions.size(); i++) {
      componentType = componentType.getComponentType();
    }
    IOSTypeBinding iosArrayBinding = typeEnv.resolveArrayType(componentType);

    IOSMethodBinding methodBinding = getMultiDimensionMethod(
        componentType, iosArrayBinding, retainedResult);
    MethodInvocation invocation =
        new MethodInvocation(methodBinding, new SimpleName(iosArrayBinding));

    // Add the dimension count argument.
    invocation.getArguments().add(NumberLiteral.newIntLiteral(dimensions.size(), typeEnv));

    // Create the dimensions array.
    ArrayInitializer dimensionsArg = new ArrayInitializer(
        GeneratedTypeBinding.newArrayType(typeEnv.resolveJavaType("int")));
    for (Expression e : dimensions) {
      dimensionsArg.getExpressions().add(e.copy());
    }
    invocation.getArguments().add(dimensionsArg);

    if (!componentType.isPrimitive()) {
      invocation.getArguments().add(newTypeLiteral(componentType));
    }

    return invocation;
  }

  private IOSMethodBinding getMultiDimensionMethod(
      ITypeBinding componentType, IOSTypeBinding arrayType, boolean retainedResult) {
    String selector = (retainedResult ? "newArray" : "array") + "WithDimensions:lengths:"
        + (componentType.isPrimitive() ? "" : "type:");
    IOSMethodBinding binding = IOSMethodBinding.newMethod(
        selector, Modifier.PUBLIC | Modifier.STATIC, typeEnv.resolveIOSType("IOSObjectArray"),
        arrayType);
    ITypeBinding intType = typeEnv.resolveJavaType("int");
    binding.addParameter(intType);
    binding.addParameter(GeneratedTypeBinding.newArrayType(intType));
    if (!componentType.isPrimitive()) {
      binding.addParameter(typeEnv.getIOSClass());
    }
    return binding;
  }

  // We must handle object array assignment before its children because if the
  // rhs is an array creation, we can optimize with "SetAndConsume".
  @Override
  public boolean visit(Assignment node) {
    Expression lhs = node.getLeftHandSide();
    ITypeBinding lhsType = lhs.getTypeBinding();
    if (lhs instanceof ArrayAccess && !lhsType.isPrimitive()) {
      FunctionInvocation newAssignment = newArrayAssignment(node, (ArrayAccess) lhs, lhsType);
      node.replaceWith(newAssignment);
      newAssignment.accept(this);
      return false;
    }
    return true;
  }

  @Override
  public void endVisit(ArrayAccess node) {
    ITypeBinding componentType = node.getTypeBinding();
    IOSTypeBinding iosArrayBinding = typeEnv.resolveArrayType(componentType);

    node.replaceWith(newArrayAccess(
        node, componentType, iosArrayBinding, TranslationUtil.isAssigned(node)));
  }

  private Expression newArrayAccess(
      ArrayAccess arrayAccessNode, ITypeBinding componentType, IOSTypeBinding iosArrayBinding,
      boolean assignable) {
    String funcName = iosArrayBinding.getName() + "_Get";
    ITypeBinding returnType = componentType;
    ITypeBinding declaredReturnType =
        componentType.isPrimitive() ? componentType : typeEnv.resolveIOSType("id");
    if (assignable) {
      funcName += "Ref";
      returnType = declaredReturnType = typeEnv.getPointerType(componentType);
    }
    FunctionInvocation invocation = new FunctionInvocation(
        funcName, returnType, declaredReturnType, iosArrayBinding);
    invocation.getArguments().add(arrayAccessNode.getArray().copy());
    invocation.getArguments().add(arrayAccessNode.getIndex().copy());
    if (assignable) {
      return new PrefixExpression(componentType, PrefixExpression.Operator.DEREFERENCE, invocation);
    }
    return invocation;
  }

  private FunctionInvocation newArrayAssignment(
      Assignment assignmentNode, ArrayAccess arrayAccessNode, ITypeBinding componentType) {
    Assignment.Operator op = assignmentNode.getOperator();
    assert !componentType.isPrimitive();
    assert op == Assignment.Operator.ASSIGN;

    Expression value = TreeUtil.remove(assignmentNode.getRightHandSide());
    Expression retainedValue = TranslationUtil.retainResult(value);
    String funcName = "IOSObjectArray_Set";
    if (retainedValue != null) {
      funcName = "IOSObjectArray_SetAndConsume";
      value = retainedValue;
    }
    FunctionInvocation invocation = new FunctionInvocation(
        funcName, componentType, typeEnv.resolveIOSType("id"),
        typeEnv.resolveIOSType("IOSObjectArray"));
    List<Expression> args = invocation.getArguments();
    args.add(TreeUtil.remove(arrayAccessNode.getArray()));
    args.add(TreeUtil.remove(arrayAccessNode.getIndex()));
    args.add(value);
    return invocation;
  }

  @Override
  public void endVisit(FieldAccess node) {
    maybeRewriteArrayLength(node, node.getName(), node.getExpression());
  }

  @Override
  public void endVisit(QualifiedName node) {
    maybeRewriteArrayLength(node, node.getName(), node.getQualifier());
  }

  /**
   * Method references which reference varargs have two very different method signatures attached to
   * their bindings. The method binding for the method reference is vararg, while the underlying
   * functional interface matches the actual argument count of the call, and is enforced at runtime.
   * We create a list of expressions for the method invocation, handling varargs by passing the
   * remaining arguments from the functional interface binding as an array in the block invocation.
   */
  public boolean createMethodReferenceInvocationArguments(MethodReference node) {
    IMethodBinding methodBinding = node.getMethodBinding();
    ITypeBinding[] methodParams = methodBinding.getParameterTypes();
    IMethodBinding functionalInterface = node.getTypeBinding().getFunctionalInterfaceMethod();
    ITypeBinding[] functionalParams = functionalInterface.getParameterTypes();
    char[] var = nameTable.incrementVariable(null);
    List<Expression> invocationArguments = node.getInvocationArguments();
    int methodParamStopIndex = methodBinding.isVarargs() ? methodParams.length - 1
        : methodParams.length;
    for (int i = 0; i < methodParamStopIndex; i++) {
      ITypeBinding functionalParam = functionalParams[i];
      IVariableBinding variableBinding = new GeneratedVariableBinding(new String(var), 0,
          functionalParam, false, true, null, null);
      invocationArguments.add(new SimpleName(variableBinding));
      var = nameTable.incrementVariable(var);
    }
    if (methodBinding.isVarargs()) {
      List<Expression> varArguments = new ArrayList<>();
      for (int i = methodParamStopIndex; i < functionalInterface.getParameterTypes().length; i++) {
        ITypeBinding functionalParam = functionalParams[i];
        IVariableBinding variableBinding = new GeneratedVariableBinding(new String(var), 0,
            functionalParam, false, true, null, null);
        varArguments.add(new SimpleName(variableBinding));
        var = nameTable.incrementVariable(var);
      }
      invocationArguments.add(
          newInitializedArrayInvocation(methodParams[methodParamStopIndex], varArguments, false));
    }
    if (!invocationArguments.isEmpty()) {
      // Add boxing and unboxing to newly created invocation expression nodes.
      new Autoboxer().run(node);
    }
    return true;
  }

  public boolean visit(CreationReference node) {
    return createMethodReferenceInvocationArguments(node);
  }

  public boolean visit(ExpressionMethodReference node) {
    return createMethodReferenceInvocationArguments(node);
  }

  public boolean visit(SuperMethodReference node) {
    return createMethodReferenceInvocationArguments(node);
  }

  public boolean visit(TypeMethodReference node) {
    return createMethodReferenceInvocationArguments(node);
  }

  private void maybeRewriteArrayLength(Expression node, SimpleName name, Expression expr) {
    ITypeBinding exprType = expr.getTypeBinding();
    if (name.getIdentifier().equals("length") && exprType.isArray()) {
      GeneratedVariableBinding sizeField = new GeneratedVariableBinding(
          "size", Modifier.PUBLIC, typeEnv.resolveJavaType("int"), true, false,
          typeEnv.mapType(exprType), null);
      node.replaceWith(new FieldAccess(sizeField, TreeUtil.remove(expr)));
    }
  }

  @Override
  public void endVisit(InstanceofExpression node) {
    ITypeBinding type = node.getRightOperand().getTypeBinding();
    if (!type.isArray() || type.getComponentType().isPrimitive()) {
      return;
    }
    IOSMethodBinding binding = IOSMethodBinding.newMethod(
        "isInstance", Modifier.PUBLIC, typeEnv.resolveJavaType("boolean"), typeEnv.getIOSClass());
    binding.addParameter(typeEnv.resolveIOSType("id"));
    MethodInvocation invocation = new MethodInvocation(binding, newTypeLiteralInvocation(type));
    invocation.getArguments().add(TreeUtil.remove(node.getLeftOperand()));
    node.replaceWith(invocation);
  }

  @Override
  public void endVisit(TypeLiteral node) {
    ITypeBinding type = node.getType().getTypeBinding();
    if (type.isArray()) {
      node.replaceWith(newTypeLiteralInvocation(type));
    }
  }

  private Expression newTypeLiteral(ITypeBinding type) {
    if (type.isArray()) {
      return newTypeLiteralInvocation(type);
    }
    return new TypeLiteral(type, typeEnv);
  }

  private FunctionInvocation newTypeLiteralInvocation(ITypeBinding type) {
    assert type.isArray();
    ITypeBinding elementType = type.getElementType();
    ITypeBinding iosClassType = typeEnv.getIOSClass();
    String funcName = elementType.isPrimitive()
        ? String.format("IOSClass_%sArray", elementType.getName()) : "IOSClass_arrayType";
    FunctionInvocation invocation = new FunctionInvocation(
        funcName, iosClassType, iosClassType, iosClassType);
    if (!elementType.isPrimitive()) {
      invocation.getArguments().add(new TypeLiteral(elementType, typeEnv));
    }
    invocation.getArguments().add(NumberLiteral.newIntLiteral(type.getDimensions(), typeEnv));
    return invocation;
  }
}
