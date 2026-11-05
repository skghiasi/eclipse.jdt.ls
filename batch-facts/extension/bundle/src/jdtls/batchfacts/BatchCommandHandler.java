package jdtls.batchfacts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.ls.core.internal.IDelegateCommandHandler;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JDTUtils.LocationType;
import org.eclipse.lsp4j.Location;

/**
 * <b>java.batch.fileFacts</b>: for each file given, where every name in it is defined, worked out in one batch pass
 * with the compiler instead of one {@code textDocument/definition} request per name.
 *
 * <pre>
 * arguments: [ [ "file:///…/A.java", … ] ]
 * result:    { "uris": [ target uri, … ],
 *              "files": [ { "uri": "file:///…/A.java",
 *                           "names": [ [ line, column, status, targetUri, targetLine, targetColumn,
 *                                        targetEndLine, targetEndColumn ], … ] }, … ] }
 * </pre>
 *
 * Each file also has {@code "overrides"}: one row for every method declared in it and every method it overrides or
 * implements, anywhere up its supertypes (the answer {@code textDocument/implementation} gives, read the other way
 * round, without a search): {@code [ line, column, endLine, endColumn,  baseUri, baseLine, baseColumn, baseEndLine,
 * baseEndColumn, baseName ]}, the first four being the range of the overriding method's name in this file and the base uri an
 * index into {@code uris}.
 *
 * And {@code "calls"}: the calls the identifier-by-identifier path cannot see, one row each:
 * {@code [ siteLine, siteColumn, siteEndLine, siteEndColumn, callerLine, callerColumn, callerEndLine, callerEndColumn,
 * calleeUri, calleeLine, calleeColumn, calleeEndLine, calleeEndColumn ]} -- object creations ({@code new X(..)}),
 * {@code this(..)} and {@code super(..)}, method references, and calls whose enclosing member is a field, an enum
 * constant or an initializer. Only callees in source files are listed.
 *
 * {@code status}: 1 = resolved (the target follows, as {@code textDocument/definition} reports it: an index into
 * {@code uris}, and the range of the target's name), 0 = the compiler says there is nothing to go to (a package
 * qualifier, the length of an array), 2 = the compiler could not resolve it, so the caller should ask
 * {@code textDocument/definition} for that one position.
 */
public class BatchCommandHandler implements IDelegateCommandHandler {
	public static final String FILE_FACTS = "java.batch.fileFacts";
	private static final int NOTHING = 0, RESOLVED = 1, UNRESOLVED = 2;

	@Override
	public Object executeCommand(String commandId, List<Object> arguments, IProgressMonitor monitor) throws Exception {
		if (!FILE_FACTS.equals(commandId)) {
			throw new UnsupportedOperationException(commandId);
		}
		List<?> uris = (List<?>) arguments.get(0);
		Map<String, Integer> targetIndex = new HashMap<>();
		List<String> targets = new ArrayList<>();
		Map<String, Object> filesByUri = new LinkedHashMap<>();

		Map<IJavaProject, List<ICompilationUnit>> byProject = new LinkedHashMap<>();
		Map<ICompilationUnit, String> uriOf = new HashMap<>();
		for (Object o : uris) {
			String uri = String.valueOf(o);
			ICompilationUnit unit = JDTUtils.resolveCompilationUnit(uri);
			if (unit == null || unit.getJavaProject() == null) {
				filesByUri.put(uri, file(uri, null, null, null, null, null, "not a compilation unit of a Java project"));
				continue;
			}
			byProject.computeIfAbsent(unit.getJavaProject(), p -> new ArrayList<>()).add(unit);
			uriOf.put(unit, uri);
		}

		String[] levelHolder = new String[1];
		for (Map.Entry<IJavaProject, List<ICompilationUnit>> entry : byProject.entrySet()) {
			IJavaProject project = entry.getKey();
			ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
			parser.setProject(project);
			parser.setResolveBindings(true);
			parser.setBindingsRecovery(true);
			parser.setStatementsRecovery(true);
			Map<String, String> options = project.getOptions(true);
			levelHolder[0] = options.get("org.eclipse.jdt.core.compiler.source");
			parser.setCompilerOptions(options);
			ICompilationUnit[] units = entry.getValue().toArray(new ICompilationUnit[0]);
			parser.createASTs(units, new String[0], new ASTRequestor() {
				@Override
				public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
					String uri = uriOf.get(source);
					try {
						filesByUri.put(uri, file(uri, names(uri, ast, targets, targetIndex), overrides(ast, targets, targetIndex), calls(source, uri, ast, targets, targetIndex), askIncomingCalls(ast), implementationBases(ast, targets, targetIndex), null));
					} catch (RuntimeException e) {
						filesByUri.put(uri, file(uri, null, null, null, null, null, String.valueOf(e)));
					}
				}
			}, monitor);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("uris", targets);
		result.put("files", new ArrayList<>(filesByUri.values()));
		result.put("level", levelHolder[0]);
		return result;
	}

	private static Map<String, Object> file(String uri, List<Object> names, List<Object> overrides, List<Object> calls, List<Object> askIncoming, List<Object> bases, String error) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("uri", uri);
		if (names != null) m.put("names", names);
		if (overrides != null) m.put("overrides", overrides);
		if (calls != null) m.put("calls", calls);
		if (askIncoming != null) m.put("askIncomingCalls", askIncoming);
		if (bases != null) m.put("implementationBases", bases);
		if (error != null) m.put("error", error);
		return m;
	}

	private static List<Object> names(String fileUri, CompilationUnit ast, List<String> targets, Map<String, Integer> targetIndex) {
		List<Object> names = new ArrayList<>();
		ast.accept(new ASTVisitor() {
			@Override
			public boolean visit(SimpleName name) {
				int offset = name.getStartPosition();
				int line = ast.getLineNumber(offset) - 1;
				int column = ast.getColumnNumber(offset);
				IBinding binding = binding(name);
				if (binding == null) {
					names.add(new Object[] { line, column, UNRESOLVED, 1 });
				} else if (binding instanceof IPackageBinding) {
					names.add(new Object[] { line, column, NOTHING });
				} else {
					Location location = locate(binding, ast, fileUri);
					if (location != null) {
						int idx = targetIndex.computeIfAbsent(location.getUri(), u -> { targets.add(u); return targets.size() - 1; });
						names.add(new Object[] { line, column, RESOLVED, idx, location.getRange().getStart().getLine(),
							location.getRange().getStart().getCharacter(), location.getRange().getEnd().getLine(),
							location.getRange().getEnd().getCharacter() });
					} else if (binding instanceof IVariableBinding v && v.isField() && v.getDeclaringClass() == null) {
						names.add(new Object[] { line, column, NOTHING }); // array.length
					} else {
						names.add(new Object[] { line, column, UNRESOLVED, binding.getKind() * 10 + (element(binding) == null ? 1 : 2) });
					}
				}
				return true;
			}
		});
		return names;
	}

	/**
	 * Where the binding is defined, the way textDocument/definition reports it: the name of the declaration; for a
	 * member of a class file without source, the class file; for a local variable, a parameter or a type variable of
	 * this file, the name where it is declared.
	 */
	private static Location locate(IBinding binding, CompilationUnit ast, String fileUri) {
		IJavaElement element = element(binding);
		if (element != null) {
			Location location = location(element);
			if (location != null) return location;
			if (element instanceof IMember member && member.getClassFile() != null) {
				try {
					Location file = JDTUtils.toLocation(member.getClassFile());
					if (file != null) return file;
				} catch (Exception e) {
					// fall through
				}
			}
		}
		ASTNode declaration = ast.findDeclaringNode(binding);
		SimpleName declared = null;
		if (declaration instanceof VariableDeclaration v) declared = v.getName();
		else if (declaration instanceof TypeParameter t) declared = t.getName();
		else if (declaration instanceof AbstractTypeDeclaration t) declared = t.getName();
		else if (declaration instanceof MethodDeclaration m) declared = m.getName();
		else if (declaration instanceof EnumConstantDeclaration c) declared = c.getName();
		if (declared == null) return null;
		int start = declared.getStartPosition(), end = start + declared.getLength();
		return new Location(fileUri, new Range(new Position(ast.getLineNumber(start) - 1, ast.getColumnNumber(start)),
			new Position(ast.getLineNumber(end) - 1, ast.getColumnNumber(end))));
	}

	/** For every method declared in the file, the methods up its supertypes that it overrides or implements. */
	private static List<Object> overrides(CompilationUnit ast, List<String> targets, Map<String, Integer> targetIndex) {
		List<Object> rows = new ArrayList<>();
		ast.accept(new ASTVisitor() {
			@Override
			public boolean visit(MethodDeclaration declaration) {
				IMethodBinding method = declaration.resolveBinding();
				if (method == null || method.isConstructor() || Modifier.isStatic(method.getModifiers())) {
					return true;
				}
				ITypeBinding declaring = method.getDeclaringClass();
				if (declaring == null) return true;
				Location own = location(method.getMethodDeclaration().getJavaElement());
				if (own == null) return true;
				Set<String> seen = new HashSet<>();
				Deque<ITypeBinding> queue = new ArrayDeque<>();
				addSupertypes(declaring, queue);
				while (!queue.isEmpty()) {
					ITypeBinding type = queue.poll();
					if (!seen.add(type.getKey())) continue;
					for (IMethodBinding candidate : type.getDeclaredMethods()) {
						if (candidate.isConstructor() || Modifier.isStatic(candidate.getModifiers()) || !method.overrides(candidate)) continue;
						Location base = location(candidate.getMethodDeclaration().getJavaElement());
						if (base == null) continue;
						int idx = targetIndex.computeIfAbsent(base.getUri(), u -> { targets.add(u); return targets.size() - 1; });
						rows.add(new Object[] { own.getRange().getStart().getLine(), own.getRange().getStart().getCharacter(),
							own.getRange().getEnd().getLine(), own.getRange().getEnd().getCharacter(), idx,
							base.getRange().getStart().getLine(), base.getRange().getStart().getCharacter(),
							base.getRange().getEnd().getLine(), base.getRange().getEnd().getCharacter(), candidate.getName() });
					}
					addSupertypes(type, queue);
				}
				return true;
			}
		});
		return rows;
	}

	private static void addSupertypes(ITypeBinding type, Deque<ITypeBinding> queue) {
		if (type.getSuperclass() != null) queue.add(type.getSuperclass());
		for (ITypeBinding i : type.getInterfaces()) queue.add(i);
	}

	private static Location location(IJavaElement element) {
		if (element == null) return null;
		try {
			return JDTUtils.toLocation(element, LocationType.NAME_RANGE);
		} catch (Exception e) {
			return null;
		}
	}

	/** The calls that are not an identifier the caller can ask about: see the class comment. */
	private static List<Object> calls(ICompilationUnit source, String fileUri, CompilationUnit ast, List<String> targets, Map<String, Integer> targetIndex) {
		List<Object> rows = new ArrayList<>();
		ast.accept(new ASTVisitor() {
			@Override
			public boolean visit(ClassInstanceCreation node) {
				IMethodBinding constructor = node.resolveConstructorBinding();
				if (node.getAnonymousClassDeclaration() != null) {
					IMethodBinding chosen = constructor == null ? null : superConstructorOf(constructor);
					// No exact match (generic parameters, say): the one constructor of the base type that takes as many arguments.
					if (chosen == null) {
						ITypeBinding base = node.getType().resolveBinding();
						IMethodBinding only = null;
						int count = 0;
						if (base != null && !base.isInterface()) {
							for (IMethodBinding candidate : base.getDeclaredMethods()) {
								if (candidate.isConstructor() && candidate.getParameterTypes().length == node.arguments().size()) {
									only = candidate;
									count++;
								}
							}
						}
						if (count == 1) chosen = only;
					}
					constructor = chosen;
				}
				// The language server reports a creation at the whole `new X(..)` expression (not its type name), and so does this;
				// an anonymous class's body is not part of the site.
				if (node.getAnonymousClassDeclaration() == null) {
					emit(node.getStartPosition(), node.getLength(), constructor, true);
				} else {
					emit(node.getType().getStartPosition(), node.getType().getLength(), constructor, true);
				}
				return true;
			}

			@Override
			public boolean visit(ConstructorInvocation node) {
				emit(node.getStartPosition(), 4, node.resolveConstructorBinding(), true);
				return true;
			}

			@Override
			public boolean visit(SuperConstructorInvocation node) {
				emit(node.getStartPosition(), 5, node.resolveConstructorBinding(), true);
				return true;
			}

			@Override
			public boolean visit(EnumConstantDeclaration node) {
				emit(node.getName().getStartPosition(), node.getName().getLength(), node.resolveConstructorBinding(), true);
				return true;
			}

			/** A class that declares no constructor has a default one, which calls the superclass's no-argument constructor. */
			@Override
			public boolean visit(TypeDeclaration node) {
				if (node.isInterface()) return true;
				for (MethodDeclaration m : node.getMethods()) {
					if (m.isConstructor()) return true;
				}
				ITypeBinding binding = node.resolveBinding();
				ITypeBinding parent = binding == null ? null : binding.getSuperclass();
				if (parent == null) return true;
				for (IMethodBinding candidate : parent.getDeclaredMethods()) {
					if (candidate.isConstructor() && candidate.getParameterTypes().length == 0) {
						emit(node.getName().getStartPosition(), node.getName().getLength(), candidate, true);
						break;
					}
				}
				return true;
			}

			/** A constructor that starts with neither {@code this(..)} nor {@code super(..)} calls the superclass's no-argument one. */
			@Override
			public boolean visit(MethodDeclaration node) {
				IMethodBinding binding = node.resolveBinding();
				if (binding == null || !binding.isConstructor() || node.getBody() == null || binding.getDeclaringClass() == null) return true;
				List<?> statements = node.getBody().statements();
				if (!statements.isEmpty() && (statements.get(0) instanceof ConstructorInvocation || statements.get(0) instanceof SuperConstructorInvocation)) return true;
				ITypeBinding parent = binding.getDeclaringClass().getSuperclass();
				if (parent == null || binding.getDeclaringClass().isEnum()) return true;
				for (IMethodBinding candidate : parent.getDeclaredMethods()) {
					if (candidate.isConstructor() && candidate.getParameterTypes().length == 0) {
						emit(node.getName().getStartPosition(), node.getName().getLength(), candidate, true);
						break;
					}
				}
				return true;
			}

			@Override
			public boolean visit(MethodInvocation node) {
				emit(node.getName().getStartPosition(), node.getName().getLength(), node.resolveMethodBinding(), false);
				return true;
			}

			@Override
			public boolean visit(SuperMethodInvocation node) {
				emit(node.getName().getStartPosition(), node.getName().getLength(), node.resolveMethodBinding(), false);
				return true;
			}

			@Override
			public boolean visit(ExpressionMethodReference node) {
				emit(node.getName().getStartPosition(), node.getName().getLength(), node.resolveMethodBinding(), true);
				return true;
			}

			@Override
			public boolean visit(SuperMethodReference node) {
				emit(node.getName().getStartPosition(), node.getName().getLength(), node.resolveMethodBinding(), true);
				return true;
			}

			@Override
			public boolean visit(TypeMethodReference node) {
				emit(node.getName().getStartPosition(), node.getName().getLength(), node.resolveMethodBinding(), true);
				return true;
			}

			@Override
			public boolean visit(CreationReference node) {
				Type type = node.getType();
				emit(type.getStartPosition(), type.getLength(), node.resolveMethodBinding(), true);
				return true;
			}

			/** {@code always}: whatever encloses it; otherwise only when it is not inside a method (a field, an initializer). */
			private void emit(int offset, int length, IMethodBinding callee, boolean always) {
				if (callee == null) return;
				IJavaElement caller;
				try {
					caller = source.getElementAt(offset);
				} catch (Exception e) {
					return;
				}
				while (caller != null && !(caller instanceof IMember)) caller = caller.getParent();
				if (caller == null) return;
				if (!always && caller instanceof IMethod) return; // the identifier path has these
				IMethodBinding declaration = callee.getMethodDeclaration();
				// An initializer block has no name: the call is the enclosing type's, as the language server reports it.
				// Nor does an anonymous class, which is where the creation of one is found when it sits in an initializer.
				while (caller instanceof IInitializer || isAnonymous(caller)) caller = caller.getParent();
				Location from = location(caller);
				Location to = location(declaration.getJavaElement());
				// A member of a local class has no element to point at; it is in this file, so its declaration names it.
				if (to == null) to = locate(declaration, ast, fileUri);
				if (from == null || to == null || !to.getUri().startsWith("file:")) return;
				int idx = targetIndex.computeIfAbsent(to.getUri(), u -> { targets.add(u); return targets.size() - 1; });
				int startLine = ast.getLineNumber(offset) - 1, startColumn = ast.getColumnNumber(offset);
				int endLine = ast.getLineNumber(offset + length) - 1, endColumn = ast.getColumnNumber(offset + length);
				rows.add(new Object[] { startLine, startColumn, endLine, endColumn,
					from.getRange().getStart().getLine(), from.getRange().getStart().getCharacter(),
					from.getRange().getEnd().getLine(), from.getRange().getEnd().getCharacter(), idx,
					to.getRange().getStart().getLine(), to.getRange().getStart().getCharacter(),
					to.getRange().getEnd().getLine(), to.getRange().getEnd().getCharacter() });
			}
		});
		return rows;
	}

	private static boolean isAnonymous(IJavaElement element) {
		try {
			return element instanceof IType type && type.isAnonymous();
		} catch (Exception e) {
			return false;
		}
	}

	/** The constructor of the superclass that an anonymous class's own (synthetic) constructor passes its arguments to. */
	private static IMethodBinding superConstructorOf(IMethodBinding anonymous) {
		ITypeBinding anonymousType = anonymous.getDeclaringClass();
		ITypeBinding parent = anonymousType == null ? null : anonymousType.getSuperclass();
		if (parent == null) return null;
		ITypeBinding[] wanted = anonymous.getParameterTypes();
		for (IMethodBinding candidate : parent.getDeclaredMethods()) {
			if (!candidate.isConstructor()) continue;
			ITypeBinding[] have = candidate.getParameterTypes();
			if (have.length != wanted.length) continue;
			boolean same = true;
			for (int i = 0; i < have.length && same; i++) {
				same = have[i].getErasure().isEqualTo(wanted[i].getErasure());
			}
			if (same) return candidate;
		}
		return null;
	}

	/** Positions of methods whose callers the client should still ask the language server for, the old way: annotation members. */
	private static List<Object> askIncomingCalls(CompilationUnit ast) {
		List<Object> rows = new ArrayList<>();
		ast.accept(new ASTVisitor() {
			@Override
			public boolean visit(AnnotationTypeMemberDeclaration node) {
				int offset = node.getName().getStartPosition();
				rows.add(new Object[] { ast.getLineNumber(offset) - 1, ast.getColumnNumber(offset) });
				return true;
			}
		});
		return rows;
	}

	/**
	 * An anonymous class whose body the compiler could not resolve (written with {@code <>} below the language level that
	 * allows it, say) has no overrides to report. For those, the methods of its base type that can be overridden are
	 * listed, so the caller asks the language server which classes override them, the old way:
	 * {@code [ baseUri, line, column, endLine, endColumn, name ]}.
	 */
	private static List<Object> implementationBases(CompilationUnit ast, List<String> targets, Map<String, Integer> targetIndex) {
		List<Object> rows = new ArrayList<>();
		Set<String> listed = new HashSet<>();
		ast.accept(new ASTVisitor() {
			@Override
			public boolean visit(AnonymousClassDeclaration node) {
				boolean unresolved = false;
				for (Object o : node.bodyDeclarations()) {
					if (o instanceof MethodDeclaration m && m.resolveBinding() == null) unresolved = true;
				}
				if (!unresolved || !(node.getParent() instanceof ClassInstanceCreation c)) return true;
				ITypeBinding base = c.getType().resolveBinding();
				if (base == null) return true;
				Deque<ITypeBinding> queue = new ArrayDeque<>();
				queue.add(base);
				Set<String> seen = new HashSet<>();
				while (!queue.isEmpty()) {
					ITypeBinding type = queue.poll();
					if (!seen.add(type.getKey())) continue;
					for (IMethodBinding m : type.getDeclaredMethods()) {
						int mod = m.getModifiers();
						if (m.isConstructor() || Modifier.isStatic(mod) || Modifier.isPrivate(mod) || Modifier.isFinal(mod)) continue;
						Location at = location(m.getMethodDeclaration().getJavaElement());
						if (at == null || !at.getUri().startsWith("file:")) continue;
						String key = at.getUri() + ":" + at.getRange().getStart().getLine() + ":" + at.getRange().getStart().getCharacter();
						if (!listed.add(key)) continue;
						int idx = targetIndex.computeIfAbsent(at.getUri(), u -> { targets.add(u); return targets.size() - 1; });
						rows.add(new Object[] { idx, at.getRange().getStart().getLine(), at.getRange().getStart().getCharacter(),
							at.getRange().getEnd().getLine(), at.getRange().getEnd().getCharacter(), m.getName() });
					}
					addSupertypes(type, queue);
				}
				return true;
			}
		});
		return rows;
	}

	/** What the name means. The type name of {@code new Foo(…)} means the constructor, as it does for go-to-definition. */
	private static IBinding binding(SimpleName name) {
		ASTNode parent = name.getParent();
		ASTNode type = parent;
		if (type instanceof SimpleType || type instanceof QualifiedType) {
			ASTNode holder = type.getParent();
			if (holder instanceof ParameterizedType p && p.getType() == type) {
				holder = p.getParent();
			}
			if (holder instanceof ClassInstanceCreation c && c.getType() == (type.getParent() instanceof ParameterizedType p2 ? p2 : type)) {
				boolean last = !(type instanceof QualifiedType q) || q.getName() == name;
				if (last) {
					IMethodBinding constructor = c.resolveConstructorBinding();
					// An anonymous class, or a class with only its default constructor, has no declaration to go to: the
					// name then means the type, as it does for go-to-definition.
					if (constructor == null && c.getAnonymousClassDeclaration() == null) {
						// The compiler could not choose a constructor (an argument it cannot type, say): leave the name
						// unresolved, so the caller asks the language server, which answers from the one name.
						return null;
					}
					if (constructor != null && c.getAnonymousClassDeclaration() == null
						&& constructor.getMethodDeclaration().getJavaElement() != null) {
						return constructor;
					}
				}
			}
		}
		return name.resolveBinding();
	}

	private static IJavaElement element(IBinding binding) {
		if (binding instanceof ITypeBinding t) return t.getTypeDeclaration().getJavaElement();
		if (binding instanceof IMethodBinding m) return m.getMethodDeclaration().getJavaElement();
		if (binding instanceof IVariableBinding v) return v.getVariableDeclaration().getJavaElement();
		return binding.getJavaElement();
	}
}
