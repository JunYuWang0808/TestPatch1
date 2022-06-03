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
package org.eclipse.jdt.core.search;

import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.compiler.*;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.compiler.parser.TerminalTokens;
import org.eclipse.jdt.internal.core.BinaryType;
import org.eclipse.jdt.internal.core.LocalVariable;
import org.eclipse.jdt.internal.core.SourceType;
import org.eclipse.jdt.internal.core.search.indexing.IIndexConstants;
import org.eclipse.jdt.internal.core.search.matching.*;

/**
 * A search pattern defines how search results are found. Use <code>SearchPattern.createPattern</code>
 * to create a search pattern.
 * <p>
 * Search patterns are used during the search phase to decode index entries that were added during the indexing phase
 * (see {@link SearchDocument#addIndexEntry(char[], char[])}). When an index is queried, the 
 * index categories and keys to consider are retrieved from the search pattern using {@link #getIndexCategories()} and
 * {@link #getIndexKey()}, as well as the match rule (see {@link #getMatchRule()}). A blank pattern is
 * then created (see {@link #getBlankPattern()}). This blank pattern is used as a record as follows.
 * For each index entry in the given index categories and that starts with the given key, the blank pattern is fed using 
 * {@link #decodeIndexKey(char[])}. The original pattern is then asked if it matches the decoded key using
 * {@link #matchesDecodedKey(SearchPattern)}. If it matches, a search doument is created for this index entry
 * using {@link SearchParticipant#getDocument(String)}.
 * 
 * </p><p>
 * This class is intended to be subclassed by clients. A default behavior is provided for each of the methods above, that
 * clients can ovveride if they wish.
 * </p>
 * @see #createPattern(org.eclipse.jdt.core.IJavaElement, int)
 * @see #createPattern(String, int, int, int)
 * @since 3.0
 */
public abstract class SearchPattern extends InternalSearchPattern {

	// Rules for pattern matching: (exact, prefix, pattern) [ | case sensitive]
	/**
	 * Match rule: The search pattern matches exactly the search result,
	 * that is, the source of the search result equals the search pattern.
	 */
	public static final int R_EXACT_MATCH = 0;
	/**
	 * Match rule: The search pattern is a prefix of the search result.
	 */
	public static final int R_PREFIX_MATCH = 1;
	/**
	 * Match rule: The search pattern contains one or more wild cards ('*') where a 
	 * wild-card can replace 0 or more characters in the search result.
	 */
	public static final int R_PATTERN_MATCH = 2;
	/**
	 * Match rule: The search pattern contains a regular expression.
	 */
	public static final int R_REGEXP_MATCH = 4;
	/**
	 * Match rule: The search pattern matches the search result only if cases are the same.
	 * Can be combined to previous rules, e.g. R_EXACT_MATCH | R_CASE_SENSITIVE
	 */
	public static final int R_CASE_SENSITIVE = 8;
	
	private int matchRule;

	/**
	 * Creates a search pattern with the rule to apply for matching index keys. 
	 * It can be exact match, prefix match, pattern match or regexp match.
	 * Rule can also be combined with a case sensitivity flag.
	 * 
	 * @param matchRule one of R_EXACT_MATCH, R_PREFIX_MATCH, R_PATTERN_MATCH, R_REGEXP_MATCH combined with R_CASE_SENSITIVE,
	 *   e.g. R_EXACT_MATCH | R_CASE_SENSITIVE if an exact and case sensitive match is requested, 
	 *   or R_PREFIX_MATCH if a prefix non case sensitive match is requested.
	 */
	public SearchPattern(int matchRule) {
		this.matchRule = matchRule;
	}

	/**
	 * Returns a search pattern that combines the given two patterns into an
	 * "and" pattern. The search result will match both the left pattern and
	 * the right pattern.
	 *
	 * @param leftPattern the left pattern
	 * @param rightPattern the right pattern
	 * @return an "and" pattern
	 */
	public static SearchPattern createAndPattern(SearchPattern leftPattern, SearchPattern rightPattern) {
		return MatchLocator.createAndPattern(leftPattern, rightPattern);
	}
	
	/**
	 * Constructor pattern are formed by [declaringQualification.]type[(parameterTypes)]
	 * e.g. java.lang.Object()
	 *		Main(*)
	 */
	private static SearchPattern createConstructorPattern(String patternString, int limitTo, int matchRule) {
	
		Scanner scanner = new Scanner(false /*comment*/, true /*whitespace*/, false /*nls*/, ClassFileConstants.JDK1_3/*sourceLevel*/, null /*taskTags*/, null/*taskPriorities*/, true/*taskCaseSensitive*/);
		scanner.setSource(patternString.toCharArray());
		final int InsideName = 1;
		final int InsideParameter = 2;
		
		String declaringQualification = null, typeName = null, parameterType = null;
		String[] parameterTypes = null;
		int parameterCount = -1;
		boolean foundClosingParenthesis = false;
		int mode = InsideName;
		int token;
		try {
			token = scanner.getNextToken();
		} catch (InvalidInputException e) {
			return null;
		}
		while (token != TerminalTokens.TokenNameEOF) {
			switch(mode) {
				// read declaring type and selector
				case InsideName :
					switch (token) {
						case TerminalTokens.TokenNameDOT:
							if (declaringQualification == null) {
								if (typeName == null) return null;
								declaringQualification = typeName;
							} else {
								String tokenSource = new String(scanner.getCurrentTokenSource());
								declaringQualification += tokenSource + typeName;
							}
							typeName = null;
							break;
						case TerminalTokens.TokenNameLPAREN:
							parameterTypes = new String[5];
							parameterCount = 0;
							mode = InsideParameter;
							break;
						case TerminalTokens.TokenNameWHITESPACE:
							break;
						default: // all other tokens are considered identifiers (see bug 21763 Problem in Java search [search])
							if (typeName == null)
								typeName = new String(scanner.getCurrentTokenSource());
							else
								typeName += new String(scanner.getCurrentTokenSource());
					}
					break;
				// read parameter types
				case InsideParameter :
					switch (token) {
						case TerminalTokens.TokenNameWHITESPACE:
							break;
						case TerminalTokens.TokenNameCOMMA:
							if (parameterType == null) return null;
							if (parameterTypes.length == parameterCount)
								System.arraycopy(parameterTypes, 0, parameterTypes = new String[parameterCount*2], 0, parameterCount);
							parameterTypes[parameterCount++] = parameterType;
							parameterType = null;
							break;
						case TerminalTokens.TokenNameRPAREN:
							foundClosingParenthesis = true;
							if (parameterType != null) {
								if (parameterTypes.length == parameterCount)
									System.arraycopy(parameterTypes, 0, parameterTypes = new String[parameterCount*2], 0, parameterCount);
								parameterTypes[parameterCount++] = parameterType;
							}
							break;
						default: // all other tokens are considered identifiers (see bug 21763 Problem in Java search [search])
							if (parameterType == null)
								parameterType = new String(scanner.getCurrentTokenSource());
							else
								parameterType += new String(scanner.getCurrentTokenSource());
					}
					break;
			}
			try {
				token = scanner.getNextToken();
			} catch (InvalidInputException e) {
				return null;
			}
		}
		// parenthesis mismatch
		if (parameterCount>0 && !foundClosingParenthesis) return null;
		if (typeName == null) return null;
	
		char[] typeNameChars = typeName.toCharArray();
		if (typeNameChars.length == 1 && typeNameChars[0] == '*') typeNameChars = null;
			
		char[] declaringQualificationChars = null;
		if (declaringQualification != null) declaringQualificationChars = declaringQualification.toCharArray();
		char[][] parameterTypeQualifications = null, parameterTypeSimpleNames = null;
	
		// extract parameter types infos
		if (parameterCount >= 0) {
			parameterTypeQualifications = new char[parameterCount][];
			parameterTypeSimpleNames = new char[parameterCount][];
			for (int i = 0; i < parameterCount; i++) {
				char[] parameterTypePart = parameterTypes[i].toCharArray();
				int lastDotPosition = CharOperation.lastIndexOf('.', parameterTypePart);
				if (lastDotPosition >= 0) {
					parameterTypeQualifications[i] = CharOperation.subarray(parameterTypePart, 0, lastDotPosition);
					if (parameterTypeQualifications[i].length == 1 && parameterTypeQualifications[i][0] == '*') {
						parameterTypeQualifications[i] = null;
					} else {
						// prefix with a '*' as the full qualification could be bigger (because of an import)
						parameterTypeQualifications[i] = CharOperation.concat(IIndexConstants.ONE_STAR, parameterTypeQualifications[i]);
					}
					parameterTypeSimpleNames[i] = CharOperation.subarray(parameterTypePart, lastDotPosition+1, parameterTypePart.length);
				} else {
					parameterTypeQualifications[i] = null;
					parameterTypeSimpleNames[i] = parameterTypePart;
				}
				if (parameterTypeSimpleNames[i].length == 1 && parameterTypeSimpleNames[i][0] == '*')
					parameterTypeSimpleNames[i] = null;
			}
		}	
		switch (limitTo) {
			case IJavaSearchConstants.DECLARATIONS :
				return new ConstructorPattern(
					true,
					false,
					typeNameChars, 
					declaringQualificationChars, 
					parameterTypeQualifications, 
					parameterTypeSimpleNames,
					false,
					matchRule);
			case IJavaSearchConstants.REFERENCES :
				return new ConstructorPattern(
					false,
					true,
					typeNameChars, 
					declaringQualificationChars, 
					parameterTypeQualifications, 
					parameterTypeSimpleNames,
					false,
					matchRule);
			case IJavaSearchConstants.ALL_OCCURRENCES :
				return new ConstructorPattern(
					true,
					true,
					typeNameChars, 
					declaringQualificationChars, 
					parameterTypeQualifications, 
					parameterTypeSimpleNames,
					false,
					matchRule);
		}
		return null;
	}
	/**
	 * Field pattern are formed by [declaringType.]name[ type]
	 * e.g. java.lang.String.serialVersionUID long
	 *		field*
	 */
	private static SearchPattern createFieldPattern(String patternString, int limitTo, int matchRule) {
		
		Scanner scanner = new Scanner(false /*comment*/, true /*whitespace*/, false /*nls*/, ClassFileConstants.JDK1_3/*sourceLevel*/, null /*taskTags*/, null/*taskPriorities*/, true/*taskCaseSensitive*/); 
		scanner.setSource(patternString.toCharArray());
		final int InsideDeclaringPart = 1;
		final int InsideType = 2;
		int lastToken = -1;
		
		String declaringType = null, fieldName = null;
		String type = null;
		int mode = InsideDeclaringPart;
		int token;
		try {
			token = scanner.getNextToken();
		} catch (InvalidInputException e) {
			return null;
		}
		while (token != TerminalTokens.TokenNameEOF) {
			switch(mode) {
				// read declaring type and fieldName
				case InsideDeclaringPart :
					switch (token) {
						case TerminalTokens.TokenNameDOT:
							if (declaringType == null) {
								if (fieldName == null) return null;
								declaringType = fieldName;
							} else {
								String tokenSource = new String(scanner.getCurrentTokenSource());
								declaringType += tokenSource + fieldName;
							}
							fieldName = null;
							break;
						case TerminalTokens.TokenNameWHITESPACE:
							if (!(TerminalTokens.TokenNameWHITESPACE == lastToken || TerminalTokens.TokenNameDOT == lastToken))
								mode = InsideType;
							break;
						default: // all other tokens are considered identifiers (see bug 21763 Problem in Java search [search])
							if (fieldName == null)
								fieldName = new String(scanner.getCurrentTokenSource());
							else
								fieldName += new String(scanner.getCurrentTokenSource());
					}
					break;
				// read type 
				case InsideType:
					switch (token) {
						case TerminalTokens.TokenNameWHITESPACE:
							break;
						default: // all other tokens are considered identifiers (see bug 21763 Problem in Java search [search])
							if (type == null)
								type = new String(scanner.getCurrentTokenSource());
							else
								type += new String(scanner.getCurrentTokenSource());
					}
					break;
			}
			lastToken = token;
			try {
				token = scanner.getNextToken();
			} catch (InvalidInputException e) {
				return null;
			}
		}
		if (fieldName == null) return null;
	
		char[] fieldNameChars = fieldName.toCharArray();
		if (fieldNameChars.length == 1 && fieldNameChars[0] == '*') fieldNameChars = null;
			
		char[] declaringTypeQualification = null, declaringTypeSimpleName = null;
		char[] typeQualification = null, typeSimpleName = null;
	
		// extract declaring type infos
		if (declaringType != null) {
			char[] declaringTypePart = declaringType.toCharArray();
			int lastDotPosition = CharOperation.lastIndexOf('.', declaringTypePart);
			if (lastDotPosition >= 0) {
				declaringTypeQualification = CharOperation.subarray(declaringTypePart, 0, lastDotPosition);
				if (declaringTypeQualification.length == 1 && declaringTypeQualification[0] == '*')
					declaringTypeQualification = null;
				declaringTypeSimpleName = CharOperation.subarray(declaringTypePart, lastDotPosition+1, declaringTypePart.length);
			} else {
				declaringTypeQualification = null;
				declaringTypeSimpleName = declaringTypePart;
			}
			if (declaringTypeSimpleName.length == 1 && declaringTypeSimpleName[0] == '*')
				declaringTypeSimpleName = null;
		}
		// extract type infos
		if (type != null) {
			char[] typePart = type.toCharArray();
			int lastDotPosition = CharOperation.lastIndexOf('.', typePart);
			if (lastDotPosition >= 0) {
				typeQualification = CharOperation.subarray(typePart, 0, lastDotPosition);
				if (typeQualification.length == 1 && typeQualification[0] == '*') {
					typeQualification = null;
				} else {
					// prefix with a '*' as the full qualification could be bigger (because of an import)
					typeQualification = CharOperation.concat(IIndexConstants.ONE_STAR, typeQualification);
				}
				typeSimpleName = CharOperation.subarray(typePart, lastDotPosition+1, typePart.length);
			} else {
				typeQualification = null;
				typeSimpleName = typePart;
			}
			if (typeSimpleName.length == 1 && typeSimpleName[0] == '*')
				typeSimpleName = null;
		}
		switch (limitTo) {
			case IJavaSearchConstants.DECLARATIONS :
				return new FieldPattern(
					true,
					false,
					false,
					fieldNameChars,
					declaringTypeQualification,
					declaringTypeSimpleName,
					typeQualification,
					typeSimpleName,
					matchRule);
			case IJavaSearchConstants.REFERENCES :
				return new FieldPattern(
					false,
					true, // read access
					true, // write access
					fieldNameChars, 
					declaringTypeQualification, 
					declaringTypeSimpleName, 
					typeQualification, 
					typeSimpleName,
					matchRule);
			case IJavaSearchConstants.READ_ACCESSES :
				return new FieldPattern(
					false,
					true, // read access only
					false,
					fieldNameChars, 
					declaringTypeQualification, 
					declaringTypeSimpleName, 
					typeQualification, 
					typeSimpleName,
					matchRule);
			case IJavaSearchConstants.WRITE_ACCESSES :
				return new FieldPattern(
					false,
					false,
					true, // write access only
					fieldNameChars, 
					declaringTypeQualification, 
					declaringTypeSimpleName, 
					typeQualification, 
					typeSimpleName,
					matchRule);
			case IJavaSearchConstants.ALL_OCCURRENCES :
				return new FieldPattern(
					true,
					true, // read access
					true, // write access
					fieldNameChars, 
					declaringTypeQualification, 
					declaringTypeSimpleName, 
					typeQualification, 
					typeSimpleName,
					matchRule);
		}
		return null;
	}
	/**
	 * Method pattern are formed by [declaringType.]selector[(parameterTypes)][ returnType]
	 * e.g. java.lang.Runnable.run() void
	 *		main(*)
	 */
	private static SearchPattern createMethodPattern(String patternString, int limitTo, int matchRule) {
		
		Scanner scanner = new Scanner(false /*comment*/, true /*whitespace*/, false /*nls*/, ClassFileConstants.JDK1_3/*sourceLevel*/, null /*taskTags*/, null/*taskPriorities*/, true/*taskCaseSensitive*/); 
		scanner.setSource(patternString.toCharArray());
		final int InsideSelector = 1;
		final int InsideParameter = 2;
		final int InsideReturnType = 3;
		int lastToken = -1;
		
		String declaringType = null, selector = null, parameterType = null;
		String[] parameterTypes = null;
		int parameterCount = -1;
		String returnType = null;
		boolean foundClosingParenthesis = false;
		int mode = InsideSelector;
		int token;
		try {
			token = scanner.getNextToken();
		} catch (InvalidInputException e) {
			return null;
		}
		while (token != TerminalTokens.TokenNameEOF) {
			switch(mode) {
				// read declaring type and selector
				case InsideSelector :
					switch (token) {
						case TerminalTokens.TokenNameDOT:
							if (declaringType == null) {
								if (selector == null) return null;
								declaringType = selector;
							} else {
								String tokenSource = new String(scanner.getCurrentTokenSource());
								declaringType += tokenSource + selector;
							}
							selector = null;
							break;
						case TerminalTokens.TokenNameLPAREN:
							parameterTypes = new String[5];
							parameterCount = 0;
							mode = InsideParameter;
							break;
						case TerminalTokens.TokenNameWHITESPACE:
							if (!(TerminalTokens.TokenNameWHITESPACE == lastToken || TerminalTokens.TokenNameDOT == lastToken))
								mode = InsideReturnType;
							break;
						default: // all other tokens are considered identifiers (see bug 21763 Problem in Java search [search])
							if (selector == null)
								selector = new String(scanner.getCurrentTokenSource());
							else
								selector += new String(scanner.getCurrentTokenSource());
							break;
					}
					break;
				// read parameter types
				case InsideParameter :
					switch (token) {
						case TerminalTokens.TokenNameWHITESPACE:
							break;
						case TerminalTokens.TokenNameCOMMA:
							if (parameterType == null) return null;
							if (parameterTypes.length == parameterCount)
								System.arraycopy(parameterTypes, 0, parameterTypes = new String[parameterCount*2], 0, parameterCount);
							parameterTypes[parameterCount++] = parameterType;
							parameterType = null;
							break;
						case TerminalTokens.TokenNameRPAREN:
							foundClosingParenthesis = true;
							if (parameterType != null){
								if (parameterTypes.length == parameterCount)
									System.arraycopy(parameterTypes, 0, parameterTypes = new String[parameterCount*2], 0, parameterCount);
								parameterTypes[parameterCount++] = parameterType;
							}
							mode = InsideReturnType;
							break;
						default: // all other tokens are considered identifiers (see bug 21763 Problem in Java search [search])
							if (parameterType == null)
								parameterType = new String(scanner.getCurrentTokenSource());
							else
								parameterType += new String(scanner.getCurrentTokenSource());
					}
					break;
				// read return type
				case InsideReturnType:
					switch (token) {
						case TerminalTokens.TokenNameWHITESPACE:
							break;
						default: // all other tokens are considered identifiers (see bug 21763 Problem in Java search [search])
							if (returnType == null)
								returnType = new String(scanner.getCurrentTokenSource());
							else
								returnType += new String(scanner.getCurrentTokenSource());
					}
					break;
			}
			lastToken = token;
			try {
				token = scanner.getNextToken();
			} catch (InvalidInputException e) {
				return null;
			}
		}
		// parenthesis mismatch
		if (parameterCount>0 && !foundClosingParenthesis) return null;
		if (selector == null) return null;
	
		char[] selectorChars = selector.toCharArray();
		if (selectorChars.length == 1 && selectorChars[0] == '*')
			selectorChars = null;
			
		char[] declaringTypeQualification = null, declaringTypeSimpleName = null;
		char[] returnTypeQualification = null, returnTypeSimpleName = null;
		char[][] parameterTypeQualifications = null, parameterTypeSimpleNames = null;
	
		// extract declaring type infos
		if (declaringType != null) {
			char[] declaringTypePart = declaringType.toCharArray();
			int lastDotPosition = CharOperation.lastIndexOf('.', declaringTypePart);
			if (lastDotPosition >= 0) {
				declaringTypeQualification = CharOperation.subarray(declaringTypePart, 0, lastDotPosition);
				if (declaringTypeQualification.length == 1 && declaringTypeQualification[0] == '*')
					declaringTypeQualification = null;
				declaringTypeSimpleName = CharOperation.subarray(declaringTypePart, lastDotPosition+1, declaringTypePart.length);
			} else {
				declaringTypeQualification = null;
				declaringTypeSimpleName = declaringTypePart;
			}
			if (declaringTypeSimpleName.length == 1 && declaringTypeSimpleName[0] == '*')
				declaringTypeSimpleName = null;
		}
		// extract parameter types infos
		if (parameterCount >= 0) {
			parameterTypeQualifications = new char[parameterCount][];
			parameterTypeSimpleNames = new char[parameterCount][];
			for (int i = 0; i < parameterCount; i++) {
				char[] parameterTypePart = parameterTypes[i].toCharArray();
				int lastDotPosition = CharOperation.lastIndexOf('.', parameterTypePart);
				if (lastDotPosition >= 0) {
					parameterTypeQualifications[i] = CharOperation.subarray(parameterTypePart, 0, lastDotPosition);
					if (parameterTypeQualifications[i].length == 1 && parameterTypeQualifications[i][0] == '*') {
						parameterTypeQualifications[i] = null;
					} else {
						// prefix with a '*' as the full qualification could be bigger (because of an import)
						parameterTypeQualifications[i] = CharOperation.concat(IIndexConstants.ONE_STAR, parameterTypeQualifications[i]);
					}
					parameterTypeSimpleNames[i] = CharOperation.subarray(parameterTypePart, lastDotPosition+1, parameterTypePart.length);
				} else {
					parameterTypeQualifications[i] = null;
					parameterTypeSimpleNames[i] = parameterTypePart;
				}
				if (parameterTypeSimpleNames[i].length == 1 && parameterTypeSimpleNames[i][0] == '*')
					parameterTypeSimpleNames[i] = null;
			}
		}	
		// extract return type infos
		if (returnType != null) {
			char[] returnTypePart = returnType.toCharArray();
			int lastDotPosition = CharOperation.lastIndexOf('.', returnTypePart);
			if (lastDotPosition >= 0) {
				returnTypeQualification = CharOperation.subarray(returnTypePart, 0, lastDotPosition);
				if (returnTypeQualification.length == 1 && returnTypeQualification[0] == '*') {
					returnTypeQualification = null;
				} else {
					// because of an import
					returnTypeQualification = CharOperation.concat(IIndexConstants.ONE_STAR, returnTypeQualification);
				}			
				returnTypeSimpleName = CharOperation.subarray(returnTypePart, lastDotPosition+1, returnTypePart.length);
			} else {
				returnTypeQualification = null;
				returnTypeSimpleName = returnTypePart;
			}
			if (returnTypeSimpleName.length == 1 && returnTypeSimpleName[0] == '*')
				returnTypeSimpleName = null;
		}
		switch (limitTo) {
			case IJavaSearchConstants.DECLARATIONS :
				return new MethodPattern(
					true,
					false,
					selectorChars, 
					declaringTypeQualification, 
					declaringTypeSimpleName, 
					returnTypeQualification, 
					returnTypeSimpleName, 
					parameterTypeQualifications, 
					parameterTypeSimpleNames,
					false,
					null,
					matchRule);
			case IJavaSearchConstants.REFERENCES :
				return new MethodPattern(
					false,
					true,
					selectorChars, 
					declaringTypeQualification, 
					declaringTypeSimpleName, 
					returnTypeQualification, 
					returnTypeSimpleName, 
					parameterTypeQualifications, 
					parameterTypeSimpleNames,
					false,
					null,
					matchRule);
			case IJavaSearchConstants.ALL_OCCURRENCES :
				return new MethodPattern(
					true,
					true,
					selectorChars, 
					declaringTypeQualification, 
					declaringTypeSimpleName, 
					returnTypeQualification, 
					returnTypeSimpleName, 
					parameterTypeQualifications, 
					parameterTypeSimpleNames,
					false,
					null,
					matchRule);
		}
		return null;
	}
	/**
	 * Returns a search pattern that combines the given two patterns into an
	 * "or" pattern. The search result will match either the left pattern or the
	 * right pattern.
	 *
	 * @param leftPattern the left pattern
	 * @param rightPattern the right pattern
	 * @return an "or" pattern
	 */
	public static SearchPattern createOrPattern(SearchPattern leftPattern, SearchPattern rightPattern) {
		return new OrPattern(leftPattern, rightPattern);
	}
	
	private static SearchPattern createPackagePattern(String patternString, int limitTo, int matchRule) {
		switch (limitTo) {
			case IJavaSearchConstants.DECLARATIONS :
				return new PackageDeclarationPattern(patternString.toCharArray(), matchRule);
			case IJavaSearchConstants.REFERENCES :
				return new PackageReferencePattern(patternString.toCharArray(), matchRule);
			case IJavaSearchConstants.ALL_OCCURRENCES :
				return new OrPattern(
					new PackageDeclarationPattern(patternString.toCharArray(), matchRule),
					new PackageReferencePattern(patternString.toCharArray(), matchRule)
				);
		}
		return null;
	}
	/**
	 * Returns a search pattern based on a given string pattern. The string patterns support '*' wild-cards.
	 * The remaining parameters are used to narrow down the type of expected results.
	 *
	 * <br>
	 *	Examples:
	 *	<ul>
	 * 		<li>search for case insensitive references to <code>Object</code>:
	 *			<code>createSearchPattern("Object", TYPE, REFERENCES, false);</code></li>
	 *  	<li>search for case sensitive references to exact <code>Object()</code> constructor:
	 *			<code>createSearchPattern("java.lang.Object()", CONSTRUCTOR, REFERENCES, true);</code></li>
	 *  	<li>search for implementers of <code>java.lang.Runnable</code>:
	 *			<code>createSearchPattern("java.lang.Runnable", TYPE, IMPLEMENTORS, true);</code></li>
	 *  </ul>
	 * @param stringPattern the given pattern
	 * @param searchFor determines the nature of the searched elements
	 *	<ul>
	 * 	<li><code>IJavaSearchConstants.CLASS</code>: only look for classes</li>
	 *		<li><code>IJavaSearchConstants.INTERFACE</code>: only look for interfaces</li>
	 * 	<li><code>IJavaSearchConstants.TYPE</code>: look for both classes and interfaces</li>
	 *		<li><code>IJavaSearchConstants.FIELD</code>: look for fields</li>
	 *		<li><code>IJavaSearchConstants.METHOD</code>: look for methods</li>
	 *		<li><code>IJavaSearchConstants.CONSTRUCTOR</code>: look for constructors</li>
	 *		<li><code>IJavaSearchConstants.PACKAGE</code>: look for packages</li>
	 *	</ul>
	 * @param limitTo determines the nature of the expected matches
	 *	<ul>
	 * 		<li><code>IJavaSearchConstants.DECLARATIONS</code>: will search declarations matching with the corresponding
	 * 			element. In case the element is a method, declarations of matching methods in subtypes will also
	 *  		be found, allowing to find declarations of abstract methods, etc.</li>
	 *
	 *		 <li><code>IJavaSearchConstants.REFERENCES</code>: will search references to the given element.</li>
	 *
	 *		 <li><code>IJavaSearchConstants.ALL_OCCURRENCES</code>: will search for either declarations or references as specified
	 *  		above.</li>
	 *
	 *		 <li><code>IJavaSearchConstants.IMPLEMENTORS</code>: for interface, will find all types which implements a given interface.</li>
	 *	</ul>
	 * @param matchRule one of R_EXACT_MATCH, R_PREFIX_MATCH, R_PATTERN_MATCH, R_REGEXP_MATCH combined with R_CASE_SENSITIVE,
	 *   e.g. R_EXACT_MATCH | R_CASE_SENSITIVE if an exact and case sensitive match is requested, 
	 *   or R_PREFIX_MATCH if a prefix non case sensitive match is requested.
	 * @return a search pattern on the given string pattern, or <code>null</code> if the string pattern is ill-formed
	 */
	public static SearchPattern createPattern(String stringPattern, int searchFor, int limitTo, int matchRule) {
		if (stringPattern == null || stringPattern.length() == 0) return null;
	
		switch (searchFor) {
			case IJavaSearchConstants.TYPE:
				return createTypePattern(stringPattern, limitTo, matchRule);
			case IJavaSearchConstants.METHOD:
				return createMethodPattern(stringPattern, limitTo, matchRule);
			case IJavaSearchConstants.CONSTRUCTOR:
				return createConstructorPattern(stringPattern, limitTo, matchRule);
			case IJavaSearchConstants.FIELD:
				return createFieldPattern(stringPattern, limitTo, matchRule);
			case IJavaSearchConstants.PACKAGE:
				return createPackagePattern(stringPattern, limitTo, matchRule);
		}
		return null;
	}
	/**
	 * Returns a search pattern based on a given Java element. 
	 * The pattern is used to trigger the appropriate search, and can be parameterized as follows:
	 *
	 * @param element the Java element the search pattern is based on
	 * @param limitTo determines the nature of the expected matches
	 * 	<ul>
	 * 		<li><code>IJavaSearchConstants.DECLARATIONS</code>: will search declarations matching with the corresponding
	 * 			element. In case the element is a method, declarations of matching methods in subtypes will also
	 *  		be found, allowing to find declarations of abstract methods, etc.</li>
	 *
	 *		 <li><code>IJavaSearchConstants.REFERENCES</code>: will search references to the given element.</li>
	 *
	 *		 <li><code>IJavaSearchConstants.ALL_OCCURRENCES</code>: will search for either declarations or references as specified
	 *  		above.</li>
	 *
	 *		 <li><code>IJavaSearchConstants.IMPLEMENTORS</code>: for interface, will find all types which implements a given interface.</li>
	 *	</ul>
	 * @return a search pattern for a Java element or <code>null</code> if the given element is ill-formed
	 */
	/* (non-Javadoc) 
	 * SEARCH_15
	 * 	Modified to handle generics:
	 * 		1) Field defined on parameterized types
	 * 			In this case, we need to split the erasure name and type argument names.
	 * 			Erasure name is used as type simple name and type argument names
	 * 			are stored in field reference pattern.
	 * 		2) Generic types declaration
	 * 			In this case, type arguments names are got from IType and stored
	 * 			in instanciated type reference pattern.
	 * 	Note that no change was done for declaration patterns as the search works well for generics
	 * 	without any additional information.
	 */
	public static SearchPattern createPattern(IJavaElement element, int limitTo) {
		SearchPattern searchPattern = null;
		int lastDot;
		switch (element.getElementType()) {
			case IJavaElement.FIELD :
				IField field = (IField) element; 
				IType declaringClass = field.getDeclaringType();
				char[] declaringSimpleName = declaringClass.getElementName().toCharArray();
				char[] declaringQualification = declaringClass.getPackageFragment().getElementName().toCharArray();
				char[][] enclosingNames = enclosingTypeNames(declaringClass);
				if (enclosingNames.length > 0)
					declaringQualification = CharOperation.concat(declaringQualification, CharOperation.concatWith(enclosingNames, '.'), '.');
				char[] name = field.getElementName().toCharArray();
				char[] typeSimpleName;
				char[] typeQualification;
				char[][] typeNames = null;
				int[] wildcards = null;
				try {
					char[] typeSignature = field.getTypeSignature().toCharArray();
					char[] typeErasure = null;
					if (CharOperation.indexOf(Signature.C_GENERIC_START, typeSignature) == -1) {
						typeErasure = Signature.toCharArray(typeSignature);
					} else {
						typeErasure = Signature.toCharArray(Signature.getTypeErasure(typeSignature));
						CharOperation.replace(typeErasure, '$', '.');
						try {
							typeNames = Signature.getTypeArguments(typeSignature);
						}
						catch (IllegalArgumentException iae) {
							// do nothing
						}
						if (typeNames != null) {
							int length = typeNames.length;
							wildcards = new int[length];
							for (int i=0; i<length; i++) {
								char[] typeName = typeNames[i];
								switch (typeName[0]) {
									case Signature.C_STAR:
										wildcards[i] = Wildcard.UNBOUND;
										break;
									case Signature.C_EXTENDS:
										wildcards[i] = Wildcard.EXTENDS;
										typeNames[i] = Signature.toCharArray(CharOperation.subarray(typeName, 1, typeName.length));
										break;
									case Signature.C_SUPER:
										wildcards[i] = Wildcard.SUPER;
										typeNames[i] = Signature.toCharArray(CharOperation.subarray(typeName, 1, typeName.length));
										break;
									default:
										wildcards[i] = -1;
										typeNames[i] = Signature.toCharArray(typeName);
										break;
								}
							}
						}
					}
					if ((lastDot = CharOperation.lastIndexOf('.', typeErasure)) == -1) {
						typeSimpleName = typeErasure;
						typeQualification = null;
					} else {
						typeSimpleName = CharOperation.subarray(typeErasure, lastDot + 1, typeErasure.length);
						typeQualification = CharOperation.subarray(typeErasure, 0, lastDot);
						if (!field.isBinary()) {
							// prefix with a '*' as the full qualification could be bigger (because of an import)
							CharOperation.concat(IIndexConstants.ONE_STAR, typeQualification);
						}
					}
				} catch (JavaModelException e) {
					return null;
				}
				switch (limitTo) {
					case IJavaSearchConstants.DECLARATIONS :
						searchPattern = 
							new FieldPattern(
								true,
								false,
								false,
								name, 
								declaringQualification, 
								declaringSimpleName, 
								typeQualification, 
								typeSimpleName,
								typeNames,
								wildcards,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.REFERENCES :
						searchPattern = 
							new FieldPattern(
								false,
								true, // read access
								true, // write access
								name, 
								declaringQualification, 
								declaringSimpleName, 
								typeQualification, 
								typeSimpleName,
								typeNames,
								wildcards,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.READ_ACCESSES :
						searchPattern = 
							new FieldPattern(
								false,
								true, // read access only
								false,
								name, 
								declaringQualification, 
								declaringSimpleName, 
								typeQualification, 
								typeSimpleName,
								typeNames,
								wildcards,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.WRITE_ACCESSES :
						searchPattern = 
							new FieldPattern(
								false,
								false,
								true, // write access only
								name, 
								declaringQualification, 
								declaringSimpleName, 
								typeQualification, 
								typeSimpleName,
								typeNames,
								wildcards,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.ALL_OCCURRENCES :
						searchPattern =
							new FieldPattern(
								true,
								true, // read access
								true, // write access
								name, 
								declaringQualification, 
								declaringSimpleName, 
								typeQualification, 
								typeSimpleName,
								typeNames,
								wildcards,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
				}
				break;
			case IJavaElement.IMPORT_DECLARATION :
				String elementName = element.getElementName();
				lastDot = elementName.lastIndexOf('.');
				if (lastDot == -1) return null; // invalid import declaration
				IImportDeclaration importDecl = (IImportDeclaration)element;
				if (importDecl.isOnDemand()) {
					searchPattern = createPackagePattern(elementName.substring(0, lastDot), limitTo, R_EXACT_MATCH | R_CASE_SENSITIVE);
				} else {
					searchPattern = 
						createTypePattern(
							elementName.substring(lastDot+1).toCharArray(),
							elementName.substring(0, lastDot).toCharArray(),
							null,
							null,
							false,
							limitTo);
				}
				break;
			case IJavaElement.LOCAL_VARIABLE :
				LocalVariable localVar = (LocalVariable) element;
				switch (limitTo) {
					case IJavaSearchConstants.DECLARATIONS :
						searchPattern = 
							new LocalVariablePattern(
								true, // declarations
								false, // no read access
								false, // no write access
								localVar,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.REFERENCES :
						searchPattern = 
							new LocalVariablePattern(
								false,
								true, // read access
								true, // write access
								localVar,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.READ_ACCESSES :
						searchPattern = 
							new LocalVariablePattern(
								false,
								true, // read access only
								false,
								localVar,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.WRITE_ACCESSES :
						searchPattern = 
							new LocalVariablePattern(
								false,
								false,
								true, // write access only
								localVar,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
					case IJavaSearchConstants.ALL_OCCURRENCES :
						searchPattern =
							new LocalVariablePattern(
								true,
								true, // read access
								true, // write access
								localVar,
								R_EXACT_MATCH | R_CASE_SENSITIVE);
						break;
				}
				break;
			case IJavaElement.METHOD :
				IMethod method = (IMethod) element;
				boolean isConstructor;
				try {
					isConstructor = method.isConstructor();
				} catch (JavaModelException e) {
					return null;
				}
				declaringClass = method.getDeclaringType();
				declaringSimpleName = declaringClass.getElementName().toCharArray();
				declaringQualification = declaringClass.getPackageFragment().getElementName().toCharArray();
				enclosingNames = enclosingTypeNames(declaringClass);
				if (enclosingNames.length > 0)
					declaringQualification = CharOperation.concat(declaringQualification, CharOperation.concatWith(enclosingNames, '.'), '.');
				char[] selector = method.getElementName().toCharArray();
				char[] returnSimpleName;
				char[] returnQualification;
				boolean varargs = false;
				try {
					String returnType = Signature.toString(method.getReturnType()).replace('$', '.');
					if ((lastDot = returnType.lastIndexOf('.')) == -1) {
						returnSimpleName = returnType.toCharArray();
						returnQualification = null;
					} else {
						returnSimpleName = returnType.substring(lastDot + 1).toCharArray();
						returnQualification = method.isBinary()
							? returnType.substring(0, lastDot).toCharArray()
							// prefix with a '*' as the full qualification could be bigger (because of an import)
							: CharOperation.concat(IIndexConstants.ONE_STAR, returnType.substring(0, lastDot).toCharArray());
					}
					varargs = Flags.isVarargs(method.getFlags());
				} catch (JavaModelException e) {
					return null;
				}
				String[] parameterTypes = method.getParameterTypes();
				int paramCount = parameterTypes.length;
				char[][] parameterSimpleNames = new char[paramCount][];
				char[][] parameterQualifications = new char[paramCount][];
				for (int i = 0; i < paramCount; i++) {
					String signature = Signature.toString(parameterTypes[i]).replace('$', '.');
					if ((lastDot = signature.lastIndexOf('.')) == -1) {
						parameterSimpleNames[i] = signature.toCharArray();
						parameterQualifications[i] = null;
					} else {
						parameterSimpleNames[i] = signature.substring(lastDot + 1).toCharArray();
						parameterQualifications[i] = method.isBinary()
							? signature.substring(0, lastDot).toCharArray()
							// prefix with a '*' as the full qualification could be bigger (because of an import)
							: CharOperation.concat(IIndexConstants.ONE_STAR, signature.substring(0, lastDot).toCharArray());
					}
				}
				switch (limitTo) {
					case IJavaSearchConstants.DECLARATIONS :
						if (isConstructor) {
							searchPattern = 
								new ConstructorPattern(
									true,
									false,
									declaringSimpleName, 
									declaringQualification, 
									parameterQualifications, 
									parameterSimpleNames,
									varargs,
									R_EXACT_MATCH | R_CASE_SENSITIVE);
						} else {
							searchPattern = 
								new MethodPattern(
									true,
									false,
									selector, 
									declaringQualification, 
									declaringSimpleName, 
									returnQualification, 
									returnSimpleName, 
									parameterQualifications, 
									parameterSimpleNames,
									varargs,
									null,
									R_EXACT_MATCH | R_CASE_SENSITIVE);
						}
						break;
					case IJavaSearchConstants.REFERENCES :
						if (isConstructor) {
							searchPattern = 
								new ConstructorPattern(
									false,
									true,
									declaringSimpleName, 
									declaringQualification, 
									parameterQualifications, 
									parameterSimpleNames,
									varargs,
									R_EXACT_MATCH | R_CASE_SENSITIVE);
						} else {
							searchPattern = 
								new MethodPattern(
									false,
									true,
									selector, 
									declaringQualification, 
									declaringSimpleName, 
									returnQualification, 
									returnSimpleName, 
									parameterQualifications, 
									parameterSimpleNames,
									varargs,
									method.getDeclaringType(),
									R_EXACT_MATCH | R_CASE_SENSITIVE);
						}
						break;
					case IJavaSearchConstants.ALL_OCCURRENCES :
						if (isConstructor) {
							searchPattern =
								new ConstructorPattern(
									true,
									true,
									declaringSimpleName, 
									declaringQualification, 
									parameterQualifications, 
									parameterSimpleNames,
									varargs,
									R_EXACT_MATCH | R_CASE_SENSITIVE);
						} else {
							searchPattern =
								new MethodPattern(
									true,
									true,
									selector, 
									declaringQualification, 
									declaringSimpleName, 
									returnQualification, 
									returnSimpleName, 
									parameterQualifications, 
									parameterSimpleNames,
									varargs,
									method.getDeclaringType(),
									R_EXACT_MATCH | R_CASE_SENSITIVE);
						}
						break;
				}
				break;
			case IJavaElement.TYPE :
				IType type = (IType)element;
				searchPattern = 	createTypePattern(
							type.getElementName().toCharArray(), 
							type.getPackageFragment().getElementName().toCharArray(),
							enclosingTypeNames(type),
							typeParameterNames(type),
							true, /* generic type */
							limitTo);
				break;
			case IJavaElement.PACKAGE_DECLARATION :
			case IJavaElement.PACKAGE_FRAGMENT :
				searchPattern = createPackagePattern(element.getElementName(), limitTo, R_EXACT_MATCH | R_CASE_SENSITIVE);
				break;
		}
		if (searchPattern != null)
			MatchLocator.setFocus(searchPattern, element);
		return searchPattern;
	}
	private static SearchPattern createTypePattern(char[] simpleName, char[] packageName, char[][] enclosingTypeNames, char[][] typeNames, boolean generic, int limitTo) {
		switch (limitTo) {
			case IJavaSearchConstants.DECLARATIONS :
				return new TypeDeclarationPattern(
					packageName, 
					enclosingTypeNames, 
					simpleName, 
					IIndexConstants.TYPE_SUFFIX,
					R_EXACT_MATCH | R_CASE_SENSITIVE);
			case IJavaSearchConstants.REFERENCES :
				return new TypeReferencePattern(
					CharOperation.concatWith(packageName, enclosingTypeNames, '.'), 
					simpleName,
					typeNames,
					generic,
					null,
					R_EXACT_MATCH | R_CASE_SENSITIVE);
			case IJavaSearchConstants.IMPLEMENTORS : 
				return new SuperTypeReferencePattern(
					CharOperation.concatWith(packageName, enclosingTypeNames, '.'), 
					simpleName,
					true,
					R_EXACT_MATCH | R_CASE_SENSITIVE);
			case IJavaSearchConstants.ALL_OCCURRENCES :
				return new OrPattern(
					new TypeDeclarationPattern(
						packageName, 
						enclosingTypeNames, 
						simpleName, 
						IIndexConstants.TYPE_SUFFIX,
						R_EXACT_MATCH | R_CASE_SENSITIVE), 
					new TypeReferencePattern(
						CharOperation.concatWith(packageName, enclosingTypeNames, '.'), 
						simpleName,
						typeNames,
						generic,
						null,
						R_EXACT_MATCH | R_CASE_SENSITIVE));
		}
		return null;
	}
	/**
	 * Type pattern are formed by [qualification.]type.
	 * e.g. java.lang.Object
	 *		Runnable
	 *
	 * @since 3.1
	 *		Type arguments can be specified to search references to parameterized types.
	 * 	Then patterns will look as follow:
	 * 		[qualification.] type [ '<' [ [ '?' {'extends'|'super'} ] type ( ',' [ '?' {'extends'|'super'} ] type )* ] '>' ]
	 * 	Please note that:
	 * 		- '*' is not valid inside type arguments definition <>
	 * 		- '?' is treated as a wildcard when it is inside <> (ie. it must be put on first position of the type argument)
	 * 		- nested <> are not allowed; List<List<Object>> will be treated as pattern List<List>
	 * 		- only one type arguments definition is allowed; Gen<Exception>.Member<Object>
	 *				will be treated as pattern Gen<Exception>.Member
	 */
	private static SearchPattern createTypePattern(String patternString, int limitTo, int matchRule) {
		
		Scanner scanner = new Scanner(false /*comment*/, true /*whitespace*/, false /*nls*/, ClassFileConstants.JDK1_3/*sourceLevel*/, null /*taskTags*/, null/*taskPriorities*/, true/*taskCaseSensitive*/); 
		scanner.setSource(patternString.toCharArray());
		String type = null;
		int token;
		try {
			token = scanner.getNextToken();
		} catch (InvalidInputException e) {
			return null;
		}
		boolean storeType = true, storeParam = true;
		int parameterized = 0;
		int paramPtr = -1;
		char[] paramName = null;
		char[][] paramNames = null;
		int[] wildcards = null;
		while (token != TerminalTokens.TokenNameEOF) {
			if (token != TerminalTokens.TokenNameWHITESPACE) {
				if (storeParam) {
					switch (token) {
						case TerminalTokens.TokenNameMULTIPLY:
							if (parameterized > 0) {
								// TODO (frederic) Should warn user that syntax is not valid
							}
							break;
						case TerminalTokens.TokenNameQUESTION:
							if (parameterized > 0) {
								if (wildcards[paramPtr] == -1) {
									wildcards[paramPtr] = Wildcard.UNBOUND;
								} else {
									// TODO (frederic) Should warn user that syntax is not valid
								}
							}
							break;
						case TerminalTokens.TokenNameextends:
							if (parameterized > 0) {
								if (wildcards[paramPtr] == Wildcard.UNBOUND) {
									wildcards[paramPtr] = Wildcard.EXTENDS;
								} else {
									// TODO (frederic) Should warn user that syntax is not valid
								}
							}
							break;
						case TerminalTokens.TokenNamesuper:
							if (parameterized > 0) {
								if (wildcards[paramPtr] == Wildcard.UNBOUND) {
									wildcards[paramPtr] = Wildcard.SUPER;
								} else {
									// TODO (frederic) Should warn user that syntax is not valid
								}
							}
							break;
						case TerminalTokens.TokenNameCOMMA:
							if (parameterized == 1 && storeParam) {
								if (paramPtr < paramNames.length) {
									paramNames[paramPtr++] = paramName;
									paramName = null;
								}
								wildcards[paramPtr] = -1;
							}
							break;
						case TerminalTokens.TokenNameGREATER:
							if (parameterized == 1) {
								if (storeParam) {
									storeParam = false;
									if (paramPtr < paramNames.length) {
										paramNames[paramPtr] = paramName;
										paramName = null;
									}
								}
							}
							parameterized--;
							break;
						case TerminalTokens.TokenNameLESS:
							if (parameterized == 0) {
								paramNames = new char[10][]; // 10 parameters max
								paramPtr++;
								wildcards = new int[10]; // 10 parameters max
								wildcards[paramPtr] = -1;
								storeType = false;
							}
							parameterized++;
							break;
						case TerminalTokens.TokenNameIdentifier:
							if (parameterized == 1 && storeParam) {
								if (paramName == null) {
									// never store id at this index
									paramName = scanner.getCurrentIdentifierSource();
								} else {
									paramName = CharOperation.concat(paramName, scanner.getCurrentIdentifierSource());
								}
							}
							break;
						case TerminalTokens.TokenNameDOT:
							if (parameterized == 1 && storeParam && paramName != null) {
								paramName = CharOperation.append(paramName, '.');
							}
							break;
					}
				}
				if (storeType) { // store type if not in type arguments declaration
					if (type == null)
						type = new String(scanner.getCurrentTokenSource());
					else
						type += new String(scanner.getCurrentTokenSource());
				}
				storeType = parameterized == 0;
			}
			try {
				token = scanner.getNextToken();
			} catch (InvalidInputException e) {
				return null;
			}
		}
		if (type == null) return null;
		// Resize param names array if necessary
		if (paramPtr >= 0) {
			System.arraycopy(paramNames, 0, paramNames = new char[paramPtr+1][], 0, paramPtr+1);
			System.arraycopy(wildcards, 0, wildcards = new int[paramPtr+1], 0, paramPtr+1);
		}
	
		char[] qualificationChars = null, typeChars = null;
	
		// extract declaring type infos
		if (type != null) {
			char[] typePart = type.toCharArray();
			int lastDotPosition = CharOperation.lastIndexOf('.', typePart);
			if (lastDotPosition >= 0) {
				qualificationChars = CharOperation.subarray(typePart, 0, lastDotPosition);
				if (qualificationChars.length == 1 && qualificationChars[0] == '*')
					qualificationChars = null;
				typeChars = CharOperation.subarray(typePart, lastDotPosition+1, typePart.length);
			} else {
				qualificationChars = null;
				typeChars = typePart;
			}
			if (typeChars.length == 1 && typeChars[0] == '*')
				typeChars = null;
		}
		switch (limitTo) {
			case IJavaSearchConstants.DECLARATIONS : // cannot search for explicit member types
				return new QualifiedTypeDeclarationPattern(qualificationChars, typeChars, IIndexConstants.TYPE_SUFFIX, matchRule);
			case IJavaSearchConstants.REFERENCES :
				return new TypeReferencePattern(qualificationChars, typeChars, paramNames, false /* not generic */, wildcards, matchRule);
			case IJavaSearchConstants.IMPLEMENTORS : 
				return new SuperTypeReferencePattern(qualificationChars, typeChars, true, matchRule);
			case IJavaSearchConstants.ALL_OCCURRENCES :
				return new OrPattern(
					new QualifiedTypeDeclarationPattern(qualificationChars, typeChars, IIndexConstants.TYPE_SUFFIX, matchRule),// cannot search for explicit member types
					new TypeReferencePattern(qualificationChars, typeChars, matchRule));
		}
		return null;
	}
	/**
	 * Returns the enclosing type names of the given type.
	 */
	private static char[][] enclosingTypeNames(IType type) {
		IJavaElement parent = type.getParent();
		switch (parent.getElementType()) {
			case IJavaElement.CLASS_FILE:
				// For a binary type, the parent is not the enclosing type, but the declaring type is.
				// (see bug 20532  Declaration of member binary type not found)
				IType declaringType = type.getDeclaringType();
				if (declaringType == null) return CharOperation.NO_CHAR_CHAR;
				return CharOperation.arrayConcat(
					enclosingTypeNames(declaringType), 
					declaringType.getElementName().toCharArray());
			case IJavaElement.COMPILATION_UNIT:
				return CharOperation.NO_CHAR_CHAR;
			case IJavaElement.FIELD:
			case IJavaElement.INITIALIZER:
			case IJavaElement.METHOD:
				IType declaringClass = ((IMember) parent).getDeclaringType();
				return CharOperation.arrayConcat(
					enclosingTypeNames(declaringClass),
					new char[][] {declaringClass.getElementName().toCharArray(), IIndexConstants.ONE_STAR});
			case IJavaElement.TYPE:
				return CharOperation.arrayConcat(
					enclosingTypeNames((IType)parent), 
					parent.getElementName().toCharArray());
			default:
				return null;
		}
	}
	/**
	 * Returns the type parameter names of the given type.
	 */
	private static char[][] typeParameterNames(IType type) {
		char[][] paramNames = null;
		try {
			if (type instanceof BinaryType) {
				paramNames = ((BinaryType) type).getTypeParameterNames();
			} else if (type instanceof SourceType) {
				paramNames = ((SourceType) type).getTypeParameterNames();
			}
		}
		catch (JavaModelException jme) {
			return null;
		}
		return paramNames;
	}
	/**
	 * Decode the given index key in this pattern. The decoded index key is used by 
	 * {@link #matchesDecodedKey(SearchPattern)} to find out if the corresponding index entry 
	 * should be considered.
	 * <p>
	 * This method should be re-implemented in subclasses that need to decode an index key.
	 * </p>
	 * 
	 * @param key the given index key
	 */
	public void decodeIndexKey(char[] key) {
		// called from findIndexMatches(), override as necessary
	}
	/**
	 * Returns a blank pattern that can be used as a record to decode an index key.
	 * <p>
	 * Implementors of this method should return a new search pattern that is going to be used
	 * to decode index keys.
	 * </p>
	 * 
	 * @return a new blank pattern
	 * @see #decodeIndexKey(char[])
	 */
	public abstract SearchPattern getBlankPattern();
	/**
	 * Returns a key to find in relevant index categories, if null then all index entries are matched.
	 * The key will be matched according to some match rule. These potential matches
	 * will be further narrowed by the match locator, but precise match locating can be expensive,
	 * and index query should be as accurate as possible so as to eliminate obvious false hits.
	 * <p>
	 * This method should be re-implemented in subclasses that need to narrow down the
	 * index query.
	 * </p>
	 * 
	 * @return an index key from this pattern, or <code>null</code> if all index entries are matched.
	 */
	public char[] getIndexKey() {
		return null; // called from queryIn(), override as necessary
	}
	/**
	 * Returns an array of index categories to consider for this index query.
	 * These potential matches will be further narrowed by the match locator, but precise
	 * match locating can be expensive, and index query should be as accurate as possible
	 * so as to eliminate obvious false hits.
	 * <p>
	 * This method should be re-implemented in subclasses that need to narrow down the
	 * index query.
	 * </p>
	 * 
	 * @return an array of index categories
	 */
	public char[][] getIndexCategories() {
		return CharOperation.NO_CHAR_CHAR; // called from queryIn(), override as necessary
	}
	/**
	 * Returns the rule to apply for matching index keys. Can be exact match, prefix match, pattern match or regexp match.
	 * Rule can also be combined with a case sensitivity flag.
	 * 
	 * @return one of R_EXACT_MATCH, R_PREFIX_MATCH, R_PATTERN_MATCH, R_REGEXP_MATCH combined with R_CASE_SENSITIVE,
	 *   e.g. R_EXACT_MATCH | R_CASE_SENSITIVE if an exact and case sensitive match is requested, 
	 *   or R_PREFIX_MATCH if a prefix non case sensitive match is requested.
	 */	
	public final int getMatchRule() {
		return this.matchRule;
	}
	/**
	 * Returns whether this pattern matches the given pattern (representing a decoded index key).
	 * <p>
	 * This method should be re-implemented in subclasses that need to narrow down the
	 * index query.
	 * </p>
	 * 
	 * @param decodedPattern a pattern representing a decoded index key
	 * @return whether this pattern matches the given pattern
	 */
	public boolean matchesDecodedKey(SearchPattern decodedPattern) {
		return true; // called from findIndexMatches(), override as necessary if index key is encoded
	}
	/**
	 * Returns whether the given name matches the given pattern.
	 * <p>
	 * This method should be re-implemented in subclasses that need to define how
	 * a name matches a pattern.
	 * </p>
	 * 
	 * @param pattern the given pattern, or <code>null</code> to represent "*"
	 * @param name the given name
	 * @return whether the given name matches the given pattern
	 */
	public boolean matchesName(char[] pattern, char[] name) {
		if (pattern == null) return true; // null is as if it was "*"
		if (name != null) {
			boolean isCaseSensitive = (this.matchRule & R_CASE_SENSITIVE) != 0;
			int matchMode = this.matchRule - (isCaseSensitive ? R_CASE_SENSITIVE : 0);
			switch (matchMode) {
				case R_EXACT_MATCH :
					return CharOperation.equals(pattern, name, isCaseSensitive);
				case R_PREFIX_MATCH :
					return CharOperation.prefixEquals(pattern, name, isCaseSensitive);
				case R_PATTERN_MATCH :
					if (!isCaseSensitive)
						pattern = CharOperation.toLowerCase(pattern);
					return CharOperation.match(pattern, name, isCaseSensitive);
				case R_REGEXP_MATCH :
					// TODO (frederic) implement regular expression match
					return true;
			}
		}
		return false;
	}
	/**
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		return "SearchPattern"; //$NON-NLS-1$
	}
}
