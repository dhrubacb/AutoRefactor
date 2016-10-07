/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013-2016 Jean-Noël Rouvignac - initial API and implementation
 * Copyright (C) 2016 Fabrice Tiercelin - Make sure we do not visit again modified nodes
 * Copyright (C) 2016 Luis Cruz - Android Refactoring Rules
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.refactoring.rules;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;

import com.sun.jndi.cosnaming.RemoteToCorba;

import static org.autorefactor.refactoring.ASTHelper.*;
import static org.eclipse.jdt.core.dom.ASTNode.*;

import java.util.List;

import org.autorefactor.refactoring.ASTBuilder;
import org.autorefactor.refactoring.ASTHelper;
import org.autorefactor.refactoring.Refactorings;

/* 
 * TODO when the last use of resource is as arg of a method invocation,
 * it should be assumed that the given method will take care of the release.  
 * TODO Track local variables. E.g., when a TypedArray a is assigned to variable b,
 * release() should be called only in one variable. 
 * TODO (low priority) check whether resources are being used after release.
 */

/** See {@link #getDescription()} method. */
public class RecycleRefactoring extends AbstractRefactoringRule {

	@Override
	public String getDescription() {
		return "Many resources, such as TypedArrays, VelocityTrackers, etc., should be "
				+ "recycled (with a recycle()/close() call) after use. "
				+ "Inspired from "
				+ "https://android.googlesource.com/platform/tools/base/+/master/lint/libs/lint-checks/src/main/java/com/android/tools/lint/checks/CleanupDetector.java";
	}

	@Override
	public String getName() {
		return "RecycleRefactoring";
	}
	
	private static boolean isMethodIgnoringParameters(MethodInvocation node,
			String typeQualifiedName, String methodName){
		if (node == null) {
            return false;
        }
        final IMethodBinding methodBinding = node.resolveMethodBinding();
        if (methodBinding == null ||
        		!methodName.equals(methodBinding.getName())){
        	return false;
        }
        final ITypeBinding declaringClazz = methodBinding.getDeclaringClass();
        final ITypeBinding implementedType =
                findImplementedType(declaringClazz, typeQualifiedName);
        return instanceOf(declaringClazz, typeQualifiedName);
	}
	
	private static boolean isMethodIgnoringParameters(MethodInvocation node,
			String typeQualifiedName, String[] methodNames){
		boolean isSameMethod;
		for(String methodName: methodNames){
			isSameMethod = isMethodIgnoringParameters(node, typeQualifiedName, methodName);
			if(isSameMethod){
				return true;
			}
		}
		return false;
	}

	private String methodNameToCleanupResource(MethodInvocation node){
		if(isMethodIgnoringParameters(
			node,
			"android.database.sqlite.SQLiteDatabase",
			new String[]{"query","rawQuery","queryWithFactory","rawQueryWithFactory"})
		){
			return "close";
		}
		else if(isMethodIgnoringParameters(
			node,
			"android.content.ContentProvider",
			new String[]{"query","rawQuery","queryWithFactory","rawQueryWithFactory"})
		){
			return "close";
		}
		else if(isMethodIgnoringParameters(
			node,
			"android.content.ContentResolver",
			new String[]{"query","rawQuery","queryWithFactory","rawQueryWithFactory"})
		){
			return "close";
		}
		else if(isMethodIgnoringParameters(
			node,
			"android.content.ContentProviderClient",
			new String[]{"query","rawQuery","queryWithFactory","rawQueryWithFactory"})
		){
			return "close";
		}
		else if(isMethodIgnoringParameters(
			node,
			"android.content.Context",
			"obtainStyledAttributes")
		){
			return "recycle";
		}
		else if(isMethod(
			node,
			"android.view.VelocityTracker",
			"obtain")
		){
			return "recycle";
		}
		else if(isMethodIgnoringParameters(
			node,
			"android.os.Handler",
			"obtainMessage")
		){
			return "recycle";
		}
		else if(isMethodIgnoringParameters(
			node,
			"android.os.Message",
			"obtain")
		){
			return "recycle";
		}
		else if(isMethod(
			node,
			"android.view.MotionEvent",
			"obtainNoHistory", "android.view.MotionEvent")
		){
			return "recycle";
		}
		else if(isMethodIgnoringParameters(
			node,
			"android.view.MotionEvent",
			"obtain")
		){
			return "recycle";
		}
		else if(isMethod(
			node,
			"android.os.Parcel",
			"obtain")
		){
			return "recycle";
		}

		else if(isMethodIgnoringParameters(
			node,
			"android.content.ContentResolver",
			"acquireContentProviderClient")
		){
			return "release";
		}
		return null;
	}
	
    @Override
    public boolean visit(MethodInvocation node) {
		final ASTBuilder b = this.ctx.getASTBuilder();
		final Refactorings r = this.ctx.getRefactorings();
		String recycleMethodName = methodNameToCleanupResource(node);
		if(recycleMethodName != null){
			SimpleName cursorExpression = null;
			ASTNode variableAssignmentNode = null;
			VariableDeclarationFragment variableDeclarationFragment = (VariableDeclarationFragment) ASTNodes.getParent(node, ASTNode.VARIABLE_DECLARATION_FRAGMENT);
			if(variableDeclarationFragment!=null){
				cursorExpression = variableDeclarationFragment.getName();
				variableAssignmentNode = variableDeclarationFragment;
			}
			else{
				Assignment variableAssignment = (Assignment) ASTNodes.getParent(node, ASTNode.ASSIGNMENT);
				cursorExpression = (SimpleName) variableAssignment.getLeftHandSide();
				variableAssignmentNode = variableAssignment;
			}
			// Check whether it has been closed
			ClosePresenceChecker closePresenceChecker = new ClosePresenceChecker(cursorExpression, recycleMethodName);
			VisitorDecorator visitor = new VisitorDecorator(variableAssignmentNode, cursorExpression, closePresenceChecker);
    		Block block = (Block) ASTNodes.getParent(node, ASTNode.BLOCK);
    		block.accept(visitor);
    		if(!closePresenceChecker.closePresent){
    			MethodInvocation closeInvocation = b.getAST().newMethodInvocation();
        		closeInvocation.setName(b.simpleName(recycleMethodName));
        		closeInvocation.setExpression(b.copy(cursorExpression));
        		ExpressionStatement expressionStatement = b.getAST().newExpressionStatement(closeInvocation);
        		Statement lastCursorAccess = closePresenceChecker.getLastCursorStatementInBlock(block);
        		if(lastCursorAccess.getNodeType() != ASTNode.RETURN_STATEMENT){
        			r.insertAfter(expressionStatement, lastCursorAccess);
        			return DO_NOT_VISIT_SUBTREE;
        		}
    		}
    	}
    	return VISIT_SUBTREE;
    }

	public class ClosePresenceChecker extends ASTVisitor {
    	public boolean closePresent;
    	private SimpleName lastCursorUse;
    	private SimpleName cursorSimpleName;
		private String recycleMethodName;
    	
    	
    	public ClosePresenceChecker(SimpleName cursorSimpleName, String recycleMethodName) {
			super();
			this.closePresent = false;
			this.lastCursorUse = null;
			this.cursorSimpleName = cursorSimpleName;
			this.recycleMethodName = recycleMethodName;
		}
    	
    	/* Returns the last statement in the block where a variable
    	 * was accessed before being assigned again or destroyed. 
         */
    	public Statement getLastCursorStatementInBlock(Block block){
    		ASTNode lastCursorStatement = this.lastCursorUse.getParent();
    		while(lastCursorStatement!=null &&
    			!block.statements().contains(lastCursorStatement)
    		){
    			lastCursorStatement = lastCursorStatement.getParent();
    		}
    		return (Statement) lastCursorStatement;
    	}


		@Override
        public boolean visit(MethodInvocation node) {
    		if(isSameLocalVariable(cursorSimpleName, node.getExpression())){
    			if(this.recycleMethodName.equals(node.getName().getIdentifier())){
    				this.closePresent=true;
    				return DO_NOT_VISIT_SUBTREE;    				
    			}
   			}
    		return VISIT_SUBTREE;
    	}
		
		@Override
        public boolean visit(SimpleName node) {
    		if(ASTHelper.isSameLocalVariable(node, cursorSimpleName)){        			
    			this.lastCursorUse = node;
    		}
    		return VISIT_SUBTREE;
    	}
		
		@Override
        public boolean visit(Assignment node) {
			if(ASTHelper.isSameLocalVariable(node.getLeftHandSide(), cursorSimpleName)){       
				return DO_NOT_VISIT_SUBTREE;
			}
			return VISIT_SUBTREE;
		}
		
		
    }
	
	/*
	 * This visitor selects a partial part of the block to make the visit
	 * I.e., it will only analyze the visitor from the variable assignment
	 *  until the next assignment or end of the block
	 *  startNode is a Assignment or VariableDeclarationFragment
	 */
	public class VisitorDecorator extends ASTVisitor {
		private ASTVisitor visitor;
		private SimpleName cursorSimpleName;
		public ASTVisitor specialVisitor;
		private ASTNode startNode;
		
		public class NopVisitor extends ASTVisitor{}
		
		VisitorDecorator(ASTNode startNode, SimpleName cursorSimpleName, ASTVisitor specialVisitor){
			this.cursorSimpleName = cursorSimpleName;
			this.specialVisitor = specialVisitor;
			this.startNode = startNode;
			this.visitor = new NopVisitor();
		}
		
		@Override
        public boolean visit(Assignment node) {
			if(node.equals(startNode)){ 
				visitor = specialVisitor;
			}
			else if(visitor != null && ASTHelper.isSameLocalVariable(node.getLeftHandSide(), cursorSimpleName)){
				visitor = new NopVisitor();
			}
			return visitor.visit(node);
		}
		
		@Override
        public boolean visit(VariableDeclarationFragment node) {
			if(node.equals(startNode)){ 
				visitor = specialVisitor;
			}
			return visitor.visit(node);
		}
		
		@Override
        public boolean visit(SimpleName node) {
			return visitor.visit(node);
		}
		
		@Override
        public boolean visit(MethodInvocation node) {
			return visitor.visit(node);
		}
	}

}
