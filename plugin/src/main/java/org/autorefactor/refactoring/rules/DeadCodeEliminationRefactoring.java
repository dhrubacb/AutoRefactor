/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2013-2016 Jean-Noël Rouvignac - initial API and implementation
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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.autorefactor.refactoring.ASTBuilder;
import org.autorefactor.refactoring.Refactorings;
import org.autorefactor.util.OnEclipseVersionUpgrade;
import org.autorefactor.util.UnhandledException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.EmptyStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchRequestor;

import static org.autorefactor.refactoring.ASTHelper.*;
import static org.eclipse.jdt.core.dom.ASTNode.*;
import static org.eclipse.jdt.core.search.IJavaSearchConstants.*;
import static org.eclipse.jdt.core.search.SearchPattern.*;

/**
 * TODO Use variable values analysis for determining where code is dead.
 *
 * @see #getDescription()
 */
@SuppressWarnings("javadoc")
public class DeadCodeEliminationRefactoring extends AbstractRefactoringRule {

    @Override
    public String getDescription() {
        return "Removes dead code.";
    }

    @Override
    public String getName() {
        return "Dead code elimination";
    }

    // TODO JNR
    // for (false) // impossible iterations
    // Remove Empty try block?
    // do this by resolvingConstantValue

    @Override
    public boolean visit(IfStatement node) {
        final ASTBuilder b = this.ctx.getASTBuilder();
        final Refactorings r = this.ctx.getRefactorings();

        final Statement thenStmt = node.getThenStatement();
        final Statement elseStmt = node.getElseStatement();
        if (elseStmt != null && asList(elseStmt).isEmpty()) {
            r.remove(elseStmt);
            return DO_NOT_VISIT_SUBTREE;
        } else if (thenStmt != null && asList(thenStmt).isEmpty()) {
            if (elseStmt != null) {
                r.replace(node,
                        b.if0(b.negate(node.getExpression()),
                                b.move(elseStmt)));
            } else {
                r.remove(node);
            }
            return DO_NOT_VISIT_SUBTREE;
        }

        final Object constantCondition = node.getExpression().resolveConstantExpressionValue();
        if (Boolean.TRUE.equals(constantCondition)) {
            r.replace(node, b.copy(thenStmt));
            if (lastStmtIsThrowOrReturn(thenStmt)) {
                r.remove(getNextSiblings(node));
            }
            return DO_NOT_VISIT_SUBTREE;
        } else if (Boolean.FALSE.equals(constantCondition)) {
            if (elseStmt != null) {
                r.replace(node, b.copy(elseStmt));
                if (lastStmtIsThrowOrReturn(elseStmt)) {
                    r.remove(getNextSiblings(node));
                }
            } else {
                r.remove(node);
            }
            return DO_NOT_VISIT_SUBTREE;
        }
        return VISIT_SUBTREE;
    }

    private boolean lastStmtIsThrowOrReturn(Statement stmt) {
        final List<Statement> stmts = asList(stmt);
        if (stmts.isEmpty()) {
            return false;
        }

        final Statement lastStmt = stmts.get(stmts.size() - 1);
        switch (lastStmt.getNodeType()) {
        case RETURN_STATEMENT:
        case THROW_STATEMENT:
            return true;

        case IF_STATEMENT:
            final IfStatement ifStmt = (IfStatement) lastStmt;
            final Statement thenStmt = ifStmt.getThenStatement();
            final Statement elseStmt = ifStmt.getElseStatement();
            return lastStmtIsThrowOrReturn(thenStmt)
                    && (elseStmt == null || lastStmtIsThrowOrReturn(elseStmt));

        default:
            return false;
        }
    }

    private List<Statement> getNextSiblings(Statement node) {
        if (node.getParent() instanceof Block) {
            final List<Statement> stmts = asList((Statement) node.getParent());
            final int indexOfNode = stmts.indexOf(node);
            final int siblingIndex = indexOfNode + 1;
            if (0 <= siblingIndex && siblingIndex < stmts.size()) {
                return stmts.subList(siblingIndex, stmts.size());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public boolean visit(WhileStatement node) {
        final Object constantCondition =
                node.getExpression().resolveConstantExpressionValue();
        if (Boolean.FALSE.equals(constantCondition)) {
            this.ctx.getRefactorings().remove(node);
            return DO_NOT_VISIT_SUBTREE;
        }
        return VISIT_SUBTREE;
    }

    @Override
    public boolean visit(TryStatement node) {
        final List<Statement> tryStmts = asList(node.getBody());
        if (tryStmts.isEmpty()) {
            final List<Statement> finallyStmts = asList(node.getFinally());
            if (!finallyStmts.isEmpty()) {
                final ASTBuilder b = this.ctx.getASTBuilder();
                this.ctx.getRefactorings().replace(node, b.copy(node.getFinally()));
                return DO_NOT_VISIT_SUBTREE;
            } else if (node.resources().isEmpty()) {
                this.ctx.getRefactorings().remove(node);
                return DO_NOT_VISIT_SUBTREE;
            }
        }
    // }else {
    // for (CatchClause catchClause : (List<CatchClause>) node.catchClauses()) {
    // final List<Statement> finallyStmts = asList(catchClause.getBody());
    // if (finallyStmts.isEmpty()) {
    // // TODO cannot remove without checking what subsequent catch clauses are
    // catching
    // this.ctx.getRefactorings().remove(catchClause);
    // }
    // }
    //
    // final List<Statement> finallyStmts = asList(node.getFinally());
    // if (finallyStmts.isEmpty()) {
    // this.ctx.getRefactorings().remove(node.getFinally());
    // }
    // // TODO If all finally and catch clauses have been removed,
    // // then we can remove the whole try statement and replace it with a simple block
    // return DO_NOT_VISIT_SUBTREE; // TODO JNR is this correct?
    // }
        return VISIT_SUBTREE;
    }

    @Override
    public boolean visit(EmptyStatement node) {
        ASTNode parent = node.getParent();
        if (parent instanceof Block) {
            this.ctx.getRefactorings().remove(node);
            return DO_NOT_VISIT_SUBTREE;
        }
        parent = getParentIgnoring(node, Block.class);
        if (parent instanceof IfStatement) {
            IfStatement is = (IfStatement) parent;
            List<Statement> thenStmts = asList(is.getThenStatement());
            List<Statement> elseStmts = asList(is.getElseStatement());
            boolean thenIsEmptyStmt = thenStmts.size() == 1 && is(thenStmts.get(0), EmptyStatement.class);
            boolean elseIsEmptyStmt = elseStmts.size() == 1 && is(elseStmts.get(0), EmptyStatement.class);
            if (thenIsEmptyStmt && elseIsEmptyStmt) {
                this.ctx.getRefactorings().remove(parent);
                return DO_NOT_VISIT_SUBTREE;
            } else if (thenIsEmptyStmt && is.getElseStatement() == null) {
                this.ctx.getRefactorings().remove(is);
                return DO_NOT_VISIT_SUBTREE;
            } else if (elseIsEmptyStmt) {
                this.ctx.getRefactorings().remove(is.getElseStatement());
                return DO_NOT_VISIT_SUBTREE;
            }
        } else if (parent instanceof TryStatement) {
            TryStatement ts = (TryStatement) parent;
            return removeEmptyStmtBody(node, ts, ts.getBody());
        } else if (parent instanceof EnhancedForStatement) {
            EnhancedForStatement efs = (EnhancedForStatement) parent;
            return removeEmptyStmtBody(node, efs, efs.getBody());
        } else if (parent instanceof ForStatement) {
            ForStatement fs = (ForStatement) parent;
            return removeEmptyStmtBody(node, fs, fs.getBody());
        } else if (parent instanceof WhileStatement) {
            WhileStatement ws = (WhileStatement) parent;
            return removeEmptyStmtBody(node, ws, ws.getBody());
        }
        return VISIT_SUBTREE;
    }

    private boolean removeEmptyStmtBody(EmptyStatement node, Statement stmt, Statement body) {
        List<Statement> bodyStmts = asList(body);
        if (bodyStmts.size() == 1 && bodyStmts.contains(node)) {
            this.ctx.getRefactorings().remove(stmt);
            return DO_NOT_VISIT_SUBTREE;
        }
        return VISIT_SUBTREE;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        if (node.getBody() == null) {
            return VISIT_SUBTREE;
        }
        List<Statement> bodyStmts = statements(node.getBody());
        if (bodyStmts.size() == 1) {
            SuperMethodInvocation bodyMi = asExpression(bodyStmts.get(0), SuperMethodInvocation.class);
            if (bodyMi != null) {
                IMethodBinding bodyMethodBinding = bodyMi.resolveMethodBinding();
                IMethodBinding declMethodBinding = node.resolveBinding();
                if (declMethodBinding.overrides(bodyMethodBinding)
                        && !hasSignificantAnnotations(declMethodBinding)
                        && haveSameModifiers(bodyMethodBinding, declMethodBinding)) {
                    if (Modifier.isProtected(declMethodBinding.getModifiers())
                            && !declaredInSamePackage(bodyMethodBinding, declMethodBinding)) {
                        // protected also means package visibility, so check if it is required
                        if (!isMethodUsedInItsPackage(declMethodBinding, node)) {
                            this.ctx.getRefactorings().remove(node);
                            return DO_NOT_VISIT_SUBTREE;
                        }
                    } else {
                        this.ctx.getRefactorings().remove(node);
                        return DO_NOT_VISIT_SUBTREE;
                    }
                }
            }
        }
        return VISIT_SUBTREE;
    }

    /** This method is extremely expensive. */
    @OnEclipseVersionUpgrade("Replace monitor.newChild(1) by monitor.split(1)")
    private boolean isMethodUsedInItsPackage(IMethodBinding methodBinding, MethodDeclaration node) {
        final IPackageBinding methodPackage = methodBinding.getDeclaringClass().getPackage();

        final AtomicBoolean methodIsUsedInPackage = new AtomicBoolean(false);
        final SearchRequestor requestor = new SearchRequestor() {
            @Override
            public void acceptSearchMatch(SearchMatch match) {
                methodIsUsedInPackage.set(true);
            }
        };

        final SubMonitor subMonitor = SubMonitor.convert(ctx.getProgressMonitor(), 1);
        final SubMonitor childMonitor = subMonitor.newChild(1);
        try {
            final SearchEngine searchEngine = new SearchEngine();
            searchEngine.search(
                    createPattern(methodBinding.getJavaElement(), REFERENCES, R_EXACT_MATCH),
                    new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
                    SearchEngine.createJavaSearchScope(new IJavaElement[] { methodPackage.getJavaElement() }),
                    requestor,
                    childMonitor);
            return methodIsUsedInPackage.get();
        } catch (CoreException e) {
            throw new UnhandledException(node, e);
        } finally {
            childMonitor.done();
        }
    }

    private boolean declaredInSamePackage(IMethodBinding methodBinding1, IMethodBinding methodBinding2) {
        final ITypeBinding declaringClass1 = methodBinding1.getDeclaringClass();
        final ITypeBinding declaringClass2 = methodBinding2.getDeclaringClass();
        return declaringClass1.getPackage().equals(declaringClass2.getPackage());
    }

    private boolean haveSameModifiers(IMethodBinding overriding, IMethodBinding overridden) {
        // UCDetector can suggest to reduce visibility where possible
        return overriding.getModifiers() == overridden.getModifiers();
    }

    private boolean hasSignificantAnnotations(IMethodBinding methodBinding) {
        for (IAnnotationBinding annotation : methodBinding.getAnnotations()) {
            ITypeBinding annotationType = annotation.getAnnotationType();
            if (!hasType(annotationType, "java.lang.Override", "java.lang.SuppressWarnings")) {
                return true;
            }
        }
        return false;
    }
}
