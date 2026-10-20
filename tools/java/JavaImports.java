import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** 用 javac 解析真实符号后整理 import；不对字符串、注释或业务表达式做正则替换。 */
public final class JavaImports {
    record Edit(int start, int end, String text) {}

    record Reference(int start, int end, TypeElement type) {}

    public static void main(String[] args) throws Exception {
        // 参数通过文件传递，避免 Windows classpath 和源码清单超出命令行长度。
        String mode = args[0];
        String classpath = Files.readString(Path.of(args[1]));
        List<String> names = Files.readAllLines(Path.of(args[2]));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var options = List.of("-proc:none", "-encoding", "UTF-8", "-classpath", classpath);
            var task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    manager,
                                    diagnostics,
                                    options,
                                    null,
                                    manager.getJavaFileObjectsFromStrings(names));
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            if (!mode.equals("literals")) task.analyze();
            var errors =
                    diagnostics.getDiagnostics().stream()
                            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                            .toList();
            if (!errors.isEmpty()) throw new IllegalStateException(errors.toString());
            var trees = Trees.instance(task);
            var pending = new HashMap<Path, String>();
            for (var unit : units) {
                Path path = Path.of(unit.getSourceFile().toUri());
                String source = Files.readString(path);
                if (mode.equals("literals")) {
                    var literals = new ArrayList<String>();
                    new TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitLiteral(LiteralTree node, Void unused) {
                            Object value = node.getValue();
                            literals.add(node.getKind() + ":" + value);
                            return super.visitLiteral(node, unused);
                        }
                    }.scan(unit, null);
                    String hash =
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            String.join("\u0000", literals)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)));
                    System.out.println(path + "\t" + hash);
                    continue;
                }
                var positions = trees.getSourcePositions();
                var unqualifiedTypes = new HashSet<TypeElement>();
                var namesInScope = new HashMap<String, Set<String>>();
                var reserved = new HashSet<String>();
                var staticReferences = new HashSet<Element>();
                var references = new ArrayList<Reference>();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitImport(ImportTree node, Void unused) {
                        return null;
                    }

                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void unused) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element instanceof TypeElement type) {
                            unqualifiedTypes.add(type);
                            register(type);
                        } else if (element != null
                                && element.getModifiers().contains(Modifier.STATIC)) {
                            staticReferences.add(element);
                        }
                        return super.visitIdentifier(node, unused);
                    }

                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        reserved.add(node.getName().toString());
                        return super.visitVariable(node, unused);
                    }

                    @Override
                    public Void visitTypeParameter(TypeParameterTree node, Void unused) {
                        reserved.add(node.getName().toString());
                        return super.visitTypeParameter(node, unused);
                    }

                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element instanceof TypeElement type) register(type);
                        return super.visitClass(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        Element element = trees.getElement(getCurrentPath());
                        if (element instanceof TypeElement type
                                && node.toString().equals(type.getQualifiedName().toString())
                                && type.getQualifiedName().toString().contains(".")) {
                            int start = (int) positions.getStartPosition(unit, node);
                            int end = (int) positions.getEndPosition(unit, node);
                            if (start >= 0 && end >= start)
                                references.add(new Reference(start, end, type));
                            register(type);
                        }
                        return super.visitMemberSelect(node, unused);
                    }

                    private void register(TypeElement type) {
                        namesInScope
                                .computeIfAbsent(
                                        type.getSimpleName().toString(), k -> new HashSet<>())
                                .add(type.getQualifiedName().toString());
                    }
                }.scan(unit, null);
                boolean needsImports = false;
                var imports = new TreeSet<String>();
                var edits = new ArrayList<Edit>();
                for (var entry : unit.getImports()) {
                    String qualified = entry.getQualifiedIdentifier().toString();
                    if (qualified.endsWith(".*")) {
                        needsImports = true;
                        String owner = qualified.substring(0, qualified.length() - 2);
                        for (var type : unqualifiedTypes) {
                            String enclosing = type.getEnclosingElement().toString();
                            if (owner.equals(enclosing))
                                imports.add("import " + type.getQualifiedName() + ";");
                        }
                        if (entry.isStatic()) {
                            TypeElement type = task.getElements().getTypeElement(owner);
                            if (type == null)
                                throw new IllegalStateException("无法解析静态 import: " + owner);
                            Set<Element> members =
                                    new HashSet<>(task.getElements().getAllMembers(type));
                            for (var element : staticReferences) {
                                if (members.contains(element))
                                    imports.add(
                                            "import static "
                                                    + owner
                                                    + "."
                                                    + element.getSimpleName()
                                                    + ";");
                            }
                        }
                    } else {
                        imports.add(
                                "import " + (entry.isStatic() ? "static " : "") + qualified + ";");
                        if (!entry.isStatic()) {
                            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
                            namesInScope
                                    .computeIfAbsent(simple, k -> new HashSet<>())
                                    .add(qualified);
                        }
                    }
                    edits.add(
                            new Edit(
                                    (int) positions.getStartPosition(unit, entry),
                                    (int) positions.getEndPosition(unit, entry),
                                    ""));
                }
                references.sort(
                        Comparator.comparingInt(Reference::start)
                                .thenComparing(Comparator.comparingInt(Reference::end).reversed()));
                int coveredThrough = -1;
                for (var reference : references) {
                    String simple = reference.type().getSimpleName().toString();
                    if (reference.start() < coveredThrough
                            || reserved.contains(simple)
                            || namesInScope.get(simple).size() > 1) continue;
                    String qualified = reference.type().getQualifiedName().toString();
                    if (reference.type().getEnclosingElement() instanceof PackageElement owner
                            && (owner.isUnnamed()
                                    || owner.getQualifiedName().contentEquals("java.lang")
                                    || owner.getQualifiedName()
                                            .contentEquals(unit.getPackageName().toString()))) {
                        // 同包和 java.lang 无需新增 import。
                    } else {
                        imports.add("import " + qualified + ";");
                    }
                    needsImports = true;
                    edits.add(new Edit(reference.start(), reference.end(), simple));
                    coveredThrough = reference.end();
                }
                // 在原 import 区域前插入，原有行尾注释保留。格式器负责排版和清理无用 import。
                int insertion =
                        unit.getImports().isEmpty() && unit.getPackageName() == null
                                ? 0
                                : unit.getImports().isEmpty()
                                        ? source.indexOf(
                                                        ';',
                                                        (int)
                                                                positions.getEndPosition(
                                                                        unit,
                                                                        unit.getPackageName()))
                                                + 1
                                        : (int)
                                                positions.getStartPosition(
                                                        unit, unit.getImports().getFirst());
                if (unit.getPackageName() == null && unit.getImports().isEmpty()) insertion = 0;
                edits.add(new Edit(insertion, insertion, "\n" + String.join("\n", imports) + "\n"));
                edits.sort(
                        Comparator.comparingInt(Edit::start)
                                .reversed()
                                .thenComparing(Comparator.comparingInt(Edit::end).reversed()));
                var result = new StringBuilder(source);
                for (var edit : edits) result.replace(edit.start(), edit.end(), edit.text());
                if (!source.contentEquals(result) && (mode.equals("rewrite") || needsImports))
                    pending.put(path, result.toString());
            }
            if (mode.equals("rewrite")) {
                for (var change : pending.entrySet())
                    Files.writeString(change.getKey(), change.getValue());
                System.out.println("已整理 import: " + pending.size() + " 个文件");
            } else if (mode.equals("check") && !pending.isEmpty()) {
                throw new IllegalStateException("存在待整理 import: " + pending.keySet());
            }
        }
    }
}
