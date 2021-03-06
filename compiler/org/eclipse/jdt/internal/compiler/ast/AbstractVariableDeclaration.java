/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.InvocationSite;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

public abstract class AbstractVariableDeclaration extends Statement implements InvocationSite {
	public int declarationEnd;
	public int declarationSourceEnd;
	public int declarationSourceStart;
	public int hiddenVariableDepth; // used to diagnose hiding scenarii
	public Expression initialization;
	public int modifiers;
	public int modifiersSourceStart;
	public Annotation[] annotations;

	public char[] name;

	public TypeReference type;
	
	public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
		return flowInfo;
	}
	
	public static final int FIELD = 1;
	public static final int INITIALIZER = 2;
	public static final int ENUM_CONSTANT = 3;
	public static final int LOCAL_VARIABLE = 4;
	public static final int PARAMETER = 5;
	public static final int TYPE_PARAMETER = 6;
	
	
	/**
	 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#genericTypeArguments()
	 */
	public TypeBinding[] genericTypeArguments() {
		return null;
	}
	
	/**
	 * Returns the constant kind of this variable declaration
	 */
	public abstract int getKind();
	
	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#isSuperAccess()
	 */
	public boolean isSuperAccess() {
		return false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#isTypeAccess()
	 */
	public boolean isTypeAccess() {
		return false;
	}

	public StringBuffer printStatement(int indent, StringBuffer output) {

		printIndent(indent, output);
		printModifiers(this.modifiers, output);
		if (type != null) {
			type.print(0, output).append(' ');
		}
		output.append(this.name); 
		switch(getKind()) {
			case ENUM_CONSTANT:
				if (initialization != null) {
					initialization.printExpression(indent, output);
				}
				return output.append(',');
			default:
				if (initialization != null) {
					output.append(" = "); //$NON-NLS-1$
					initialization.printExpression(indent, output);
				}
				return output.append(';');
		}
	}

	public void resolve(BlockScope scope) {
		// do nothing by default (redefined for local variables)
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#setActualReceiverType(org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding)
	 */
	public void setActualReceiverType(ReferenceBinding receiverType) {
		// do nothing by default
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#setDepth(int)
	 */
	public void setDepth(int depth) {

		this.hiddenVariableDepth = depth;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#setFieldIndex(int)
	 */
	public void setFieldIndex(int depth) {
		// do nothing by default
	}
}
