/*
 * Sonar Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.resolve;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.AstNodeType;
import org.sonar.java.ast.api.JavaKeyword;
import org.sonar.java.ast.api.JavaTokenType;
import org.sonar.java.ast.parser.JavaGrammar;
import org.sonar.java.ast.visitors.JavaAstVisitor;
import org.sonar.java.ast.visitors.MethodHelper;

import java.util.List;

/**
 * Defines scopes and symbols.
 */
public class FirstPass extends JavaAstVisitor {

  private final SemanticModel semanticModel;

  private final AstNodeType[] scopeAndSymbolAstNodeTypes;
  private final AstNodeType[] scopeAstNodeTypes;
  private final AstNodeType[] symbolAstNodeTypes;

  private Symbol enclosingSymbol;

  private Resolve.Env env;

  private final List<Symbol> uncompleted = Lists.newArrayList();
  private final SecondPass completer;

  public FirstPass(SemanticModel semanticModel, Resolve resolve) {
    this.semanticModel = semanticModel;
    this.completer = new SecondPass(semanticModel, resolve);
    scopeAndSymbolAstNodeTypes = new AstNodeType[]{
      JavaGrammar.COMPILATION_UNIT,
      JavaGrammar.CLASS_DECLARATION,
      JavaGrammar.INTERFACE_DECLARATION,
      JavaGrammar.ENUM_DECLARATION,
      JavaGrammar.ANNOTATION_TYPE_DECLARATION,
      // Method or constructor
      JavaGrammar.METHOD_DECLARATOR_REST,
      JavaGrammar.VOID_METHOD_DECLARATOR_REST,
      JavaGrammar.CONSTRUCTOR_DECLARATOR_REST,
      JavaGrammar.INTERFACE_METHOD_DECLARATOR_REST,
      JavaGrammar.VOID_INTERFACE_METHOD_DECLARATORS_REST,
      JavaGrammar.ANNOTATION_METHOD_REST};
    scopeAstNodeTypes = new AstNodeType[]{
      JavaGrammar.BLOCK,
      JavaGrammar.FOR_STATEMENT};
    symbolAstNodeTypes = new AstNodeType[]{
      JavaGrammar.FIELD_DECLARATION,
      JavaGrammar.ENUM_CONSTANT,
      JavaGrammar.CONSTANT_DECLARATOR_REST,
      JavaGrammar.FORMAL_PARAMETERS_DECLS_REST,
      JavaGrammar.LOCAL_VARIABLE_DECLARATION_STATEMENT,
      JavaGrammar.FOR_INIT,
      JavaGrammar.FORMAL_PARAMETER,
      JavaGrammar.CATCH_FORMAL_PARAMETER,
      JavaGrammar.RESOURCE,
    };
  }

  @Override
  public void init() {
    subscribeTo(scopeAndSymbolAstNodeTypes);
    subscribeTo(scopeAstNodeTypes);
    subscribeTo(symbolAstNodeTypes);
  }

  @Override
  public void visitNode(AstNode astNode) {
    if (astNode.is(JavaGrammar.COMPILATION_UNIT)) {
      visitCompilationUnit(astNode);
    } else if (astNode.is(JavaGrammar.CLASS_DECLARATION, JavaGrammar.INTERFACE_DECLARATION, JavaGrammar.ENUM_DECLARATION, JavaGrammar.ANNOTATION_TYPE_DECLARATION)) {
      visitClassDeclaration(astNode);
    } else if (astNode.is(
      JavaGrammar.METHOD_DECLARATOR_REST,
      JavaGrammar.VOID_METHOD_DECLARATOR_REST,
      JavaGrammar.CONSTRUCTOR_DECLARATOR_REST,
      JavaGrammar.INTERFACE_METHOD_DECLARATOR_REST,
      JavaGrammar.VOID_INTERFACE_METHOD_DECLARATORS_REST,
      JavaGrammar.ANNOTATION_METHOD_REST)) {
      visitMethodDeclaration(astNode);
    } else if (astNode.is(JavaGrammar.ENUM_CONSTANT)) {
      visitEnumConstant(astNode);
    } else if (astNode.is(JavaGrammar.FIELD_DECLARATION)) {
      visitFieldDeclaration(astNode);
    } else if (astNode.is(JavaGrammar.CONSTANT_DECLARATOR_REST)) {
      visitConstantDeclaration(astNode);
    } else if (astNode.is(JavaGrammar.FORMAL_PARAMETERS_DECLS_REST)) {
      visitMethodParameter(astNode);
    } else if (astNode.is(JavaGrammar.LOCAL_VARIABLE_DECLARATION_STATEMENT)) {
      visitLocalVariableDeclarationStatement(astNode);
    } else if (astNode.is(JavaGrammar.FOR_INIT)) {
      visitForInit(astNode);
    } else if (astNode.is(JavaGrammar.FORMAL_PARAMETER)) {
      visitForFormalParameter(astNode);
    } else if (astNode.is(JavaGrammar.CATCH_FORMAL_PARAMETER)) {
      visitCatchFormalParameter(astNode);
    } else if (astNode.is(JavaGrammar.RESOURCE)) {
      visitResource(astNode);
    } else if (astNode.is(JavaGrammar.BLOCK)) {
      visitBlockStatement(astNode);
    } else if (astNode.is(JavaGrammar.FOR_STATEMENT)) {
      visitForStatement(astNode);
    } else {
      throw new IllegalArgumentException("Unexpected AstNodeType: " + astNode.getType());
    }
  }

  @Override
  public void leaveNode(AstNode astNode) {
    if (astNode.is(scopeAndSymbolAstNodeTypes)) {
      restoreEnvironment(astNode);
      enclosingSymbol = enclosingSymbol.owner();
    } else if (astNode.is(scopeAstNodeTypes)) {
      restoreEnvironment(astNode);
    } else if (astNode.is(symbolAstNodeTypes)) {
      enclosingSymbol = enclosingSymbol.owner();
    } else {
      throw new IllegalArgumentException("Unexpected AstNodeType: " + astNode.getType());
    }
  }

  private void restoreEnvironment(AstNode astNode) {
    if (env.next == null) {
      // Invariant: env.next == null for CompilationUnit
      Preconditions.checkState(astNode.is(JavaGrammar.COMPILATION_UNIT));
    } else {
      env = env.next;
    }
  }

  @Override
  public void leaveFile(AstNode astNode) {
    for (Symbol symbol : uncompleted) {
      symbol.complete();
    }
    uncompleted.clear();
  }

  private void visitCompilationUnit(AstNode astNode) {
    // TODO package and imports
    Symbol.PackageSymbol symbol = new Symbol.PackageSymbol(null, null);
    symbol.members = new Scope();

    env = new Resolve.Env();
    env.packge = symbol;
    env.scope = symbol.members;

    enclosingSymbol = env.packge;
    if (astNode.hasDirectChildren(JavaGrammar.PACKAGE_DECLARATION)) {
      // named package
    } else {
      // unnamed package
    }
  }

  private void visitClassDeclaration(AstNode astNode) {
    AstNode identifierNode = astNode.getFirstChild(JavaTokenType.IDENTIFIER);
    String name = identifierNode.getTokenValue();
    Symbol.TypeSymbol symbol = new Symbol.TypeSymbol(computeClassFlags(astNode), name, enclosingSymbol);
    enterSymbol(identifierNode, symbol);
    symbol.members = new Scope();
    symbol.completer = completer;
    uncompleted.add(symbol);

    // Save current environment to be able to complete class later
    semanticModel.saveEnv(symbol, env);

    Resolve.Env classEnv = env.dup();
    classEnv.outer = env;
    classEnv.enclosingClass = symbol;
    classEnv.scope = symbol.members;
    env = classEnv;
    semanticModel.associateEnv(astNode, env);
  }

  private int computeModifierFlag(AstNode astNode) {
    Preconditions.checkArgument(astNode.is(JavaGrammar.MODIFIER));
    final int flag;
    AstNode modifierNode = astNode.getFirstChild();
    if (modifierNode.is(JavaKeyword.PRIVATE)) {
      flag = Flags.PRIVATE;
    } else if (modifierNode.is(JavaKeyword.PROTECTED)) {
      flag = Flags.PROTECTED;
    } else if (modifierNode.is(JavaKeyword.PUBLIC)) {
      flag = Flags.PUBLIC;
    } else {
      flag = 0;
    }
    return flag;
  }

  private int computeClassFlags(AstNode astNode) {
    int flags = 0;
    AstNode modifierNode = astNode.getPreviousAstNode();
    while (modifierNode != null && modifierNode.is(JavaGrammar.MODIFIER)) {
      flags |= computeModifierFlag(modifierNode);
      modifierNode = modifierNode.getPreviousAstNode();
    }
    if (astNode.is(JavaGrammar.INTERFACE_DECLARATION, JavaGrammar.ANNOTATION_TYPE_DECLARATION)) {
      flags |= Flags.INTERFACE;
    }
    if (enclosingSymbol instanceof Symbol.TypeSymbol && ((env.enclosingClass.flags() & Flags.INTERFACE) != 0)) {
      // JLS7 6.6.1: All members of interfaces are implicitly public.
      flags |= Flags.PUBLIC;
    }
    return flags;
  }

  private void visitMethodDeclaration(AstNode astNode) {
    MethodHelper methodHelper = new MethodHelper(astNode);
    AstNode identifierNode = methodHelper.getName();
    String name = methodHelper.isConstructor() ? "<init>" : identifierNode.getTokenValue();
    Symbol.MethodSymbol symbol = new Symbol.MethodSymbol(computeMethodFlags(astNode), name, enclosingSymbol);
    enterSymbol(identifierNode, symbol);
    symbol.parameters = new Scope();
    symbol.completer = completer;
    uncompleted.add(symbol);

    // Save current environment to be able to complete method later
    semanticModel.saveEnv(symbol, env);

    // Create new environment - this is required, because new scope is created
    Resolve.Env methodEnv = env.dup();
    methodEnv.scope = symbol.parameters;
    env = methodEnv;
  }

  private int computeMethodFlags(AstNode astNode) {
    if (astNode.is(JavaGrammar.METHOD_DECLARATOR_REST, JavaGrammar.VOID_METHOD_DECLARATOR_REST, JavaGrammar.CONSTRUCTOR_DECLARATOR_REST)) {
      AstNode node = astNode.getFirstAncestor(JavaGrammar.CLASS_BODY_DECLARATION);
      int flags = 0;
      for (AstNode modifierNode : node.getChildren(JavaGrammar.MODIFIER)) {
        flags |= computeModifierFlag(modifierNode);
      }
      return flags;
    } else if (astNode.is(JavaGrammar.INTERFACE_METHOD_DECLARATOR_REST, JavaGrammar.VOID_INTERFACE_METHOD_DECLARATORS_REST)) {
      // JLS7 6.6.1: All members of interfaces are implicitly public.
      return Flags.PUBLIC;
    } else if (astNode.is(JavaGrammar.ANNOTATION_METHOD_REST)) {
      // JLS7 6.6.1: All members of interfaces are implicitly public.
      return Flags.PUBLIC;
    } else {
      throw new IllegalArgumentException("Unexpected AstNodeType: " + astNode.getType());
    }
  }

  private void visitEnumConstant(AstNode astNode) {
    declareVariable(Flags.PUBLIC, astNode.getFirstChild(JavaTokenType.IDENTIFIER));
  }

  private void visitFieldDeclaration(AstNode astNode) {
    int flags = computeClassFlags(astNode);
    for (AstNode variableDeclaratorNode : astNode.getFirstChild(JavaGrammar.VARIABLE_DECLARATORS).getChildren(JavaGrammar.VARIABLE_DECLARATOR)) {
      declareVariable(flags, variableDeclaratorNode.getFirstChild(JavaTokenType.IDENTIFIER));
    }
  }

  private void visitConstantDeclaration(AstNode astNode) {
    // JLS7 6.6.1: All members of interfaces are implicitly public.
    declareVariable(Flags.PUBLIC, astNode.getPreviousAstNode());
  }

  private void visitMethodParameter(AstNode astNode) {
    declareVariable(0, astNode.getFirstChild(JavaGrammar.VARIABLE_DECLARATOR_ID).getFirstChild(JavaTokenType.IDENTIFIER));
  }

  private void visitLocalVariableDeclarationStatement(AstNode astNode) {
    for (AstNode variableDeclaratorNode : astNode.getFirstChild(JavaGrammar.VARIABLE_DECLARATORS).getChildren(JavaGrammar.VARIABLE_DECLARATOR)) {
      declareVariable(0, variableDeclaratorNode.getFirstChild(JavaTokenType.IDENTIFIER));
    }
  }

  private void visitForInit(AstNode astNode) {
    if (astNode.hasDirectChildren(JavaGrammar.VARIABLE_DECLARATORS)) {
      for (AstNode variableDeclaratorNode : astNode.getFirstChild(JavaGrammar.VARIABLE_DECLARATORS).getChildren(JavaGrammar.VARIABLE_DECLARATOR)) {
        declareVariable(0, variableDeclaratorNode.getFirstChild(JavaTokenType.IDENTIFIER));
      }
    }
  }

  private void visitForFormalParameter(AstNode astNode) {
    declareVariable(0, astNode.getFirstChild(JavaGrammar.VARIABLE_DECLARATOR_ID).getFirstChild(JavaTokenType.IDENTIFIER));
  }

  private void visitCatchFormalParameter(AstNode astNode) {
    declareVariable(0, astNode.getFirstChild(JavaGrammar.VARIABLE_DECLARATOR_ID).getFirstChild(JavaTokenType.IDENTIFIER));
  }

  private void visitResource(AstNode astNode) {
    declareVariable(0, astNode.getFirstChild(JavaGrammar.VARIABLE_DECLARATOR_ID).getFirstChild(JavaTokenType.IDENTIFIER));
  }

  private void declareVariable(int flags, AstNode identifierNode) {
    Preconditions.checkArgument(identifierNode.is(JavaTokenType.IDENTIFIER));

    String name = identifierNode.getTokenValue();
    Symbol.VariableSymbol symbol = new Symbol.VariableSymbol(flags, name, enclosingSymbol);
    enterSymbol(identifierNode, symbol);
    symbol.completer = completer;
    uncompleted.add(symbol);

    // Save current environment to be able to complete variable declaration later
    semanticModel.saveEnv(symbol, env);
  }

  private void visitBlockStatement(AstNode astNode) {
    Scope scope = new Scope(env.scope);

    // Create new environment - this is required, because block can declare types
    Resolve.Env blockEnv = env.dup();
    blockEnv.scope = scope;
    env = blockEnv;
    semanticModel.associateEnv(astNode, env);
  }

  private void visitForStatement(AstNode astNode) {
    Scope scope = new Scope(env.scope);

    // Create new environment - this is required, because new scope is created
    Resolve.Env forEnv = env.dup();
    forEnv.scope = scope;
    env = forEnv;
    semanticModel.associateEnv(astNode, env);
  }

  private void enterSymbol(AstNode astNode, Symbol symbol) {
    env.scope.enter(symbol);
    semanticModel.associateSymbol(astNode, symbol);
    enclosingSymbol = symbol;
  }

}
