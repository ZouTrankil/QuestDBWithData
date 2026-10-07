package com.zoutrankil.architecture;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.attribute.*;
import java.lang.classfile.constantpool.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Offline dependency checks using the JDK class-file API; no application classes are initialized. */
final class ArchitectureRules {
    record Edge(String origin, String target, boolean member) {}
    record Violation(String rule, String origin, String target) implements Comparable<Violation> {
        String key() { return rule + "\t" + origin + "\t" + target; }
        @Override public int compareTo(Violation other) { return key().compareTo(other.key()); }
    }
    private static final Pattern INTERNAL_TYPE = Pattern.compile(
            "(?:com/zoutrankil|org/springframework|java/sql|javax/sql|io/questdb)/[A-Za-z0-9_$/]+");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile(
            "(?:com\\.zoutrankil|org\\.springframework|java\\.sql|javax\\.sql|io\\.questdb)(?:\\.[A-Za-z_$][\\w$]*)+");
    private static final Pattern NON_CODE = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Set<String> JDBC_TYPES = Set.of("Connection", "DriverManager", "Statement",
            "PreparedStatement", "CallableStatement", "ResultSet", "DatabaseMetaData");
    private static final Set<String> PORT_CONTAINERS = Set.of(
            "com.zoutrankil.data.service.VerifiedBatchExecutor", "com.zoutrankil.data.service.SyncJobRunner");

    private ArchitectureRules() {}

    static Set<Edge> bytecodeEdges(byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        String origin = topLevel(model.thisClass().asInternalName());
        var result = new HashSet<Edge>();
        for (var entry : model.constantPool()) {
            if (entry instanceof ClassEntry type) addTypes(result, origin, type.asInternalName());
            if (entry instanceof NameAndTypeEntry memberType) addTypes(result, origin, memberType.type().stringValue());
            if (entry instanceof MemberRefEntry member) {
                result.add(new Edge(origin, member.owner().asInternalName().replace('/', '.'), true));
                addTypes(result, origin, member.type().stringValue());
            }
        }
        // A descriptor may share a UTF8 entry with a string literal; actual declarations still count.
        model.fields().forEach(field -> {
            addTypes(result, origin, field.fieldType().stringValue());
            annotationTypes(result, origin, field);
            field.findAttribute(java.lang.classfile.Attributes.signature())
                    .ifPresent(signature -> addTypes(result, origin, signature.signature().stringValue()));
        });
        model.methods().forEach(method -> {
            addTypes(result, origin, method.methodType().stringValue());
            annotationTypes(result, origin, method);
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof RuntimeVisibleTypeAnnotationsAttribute annotations)
                    annotations.annotations().forEach(value -> annotation(result, origin, value.annotation()));
                if (element instanceof RuntimeInvisibleTypeAnnotationsAttribute annotations)
                    annotations.annotations().forEach(value -> annotation(result, origin, value.annotation()));
            }));
            method.findAttribute(java.lang.classfile.Attributes.signature())
                    .ifPresent(signature -> addTypes(result, origin, signature.signature().stringValue()));
        });
        model.findAttribute(java.lang.classfile.Attributes.signature())
                .ifPresent(signature -> addTypes(result, origin, signature.signature().stringValue()));
        annotationTypes(result, origin, model);
        return result;
    }

    private static void annotationTypes(Set<Edge> edges, String origin, AttributedElement element) {
        for (var attribute : element.attributes()) {
            if (attribute instanceof RuntimeVisibleAnnotationsAttribute values)
                values.annotations().forEach(value -> annotation(edges, origin, value));
            if (attribute instanceof RuntimeInvisibleAnnotationsAttribute values)
                values.annotations().forEach(value -> annotation(edges, origin, value));
            if (attribute instanceof RuntimeVisibleTypeAnnotationsAttribute values)
                values.annotations().forEach(value -> annotation(edges, origin, value.annotation()));
            if (attribute instanceof RuntimeInvisibleTypeAnnotationsAttribute values)
                values.annotations().forEach(value -> annotation(edges, origin, value.annotation()));
            if (attribute instanceof RuntimeVisibleParameterAnnotationsAttribute values)
                values.parameterAnnotations().forEach(parameter -> parameter.forEach(value -> annotation(edges, origin, value)));
            if (attribute instanceof RuntimeInvisibleParameterAnnotationsAttribute values)
                values.parameterAnnotations().forEach(parameter -> parameter.forEach(value -> annotation(edges, origin, value)));
            if (attribute instanceof AnnotationDefaultAttribute value) annotationValue(edges, origin, value.defaultValue());
        }
    }
    private static void annotation(Set<Edge> edges, String origin, Annotation annotation) {
        addTypes(edges, origin, annotation.className().stringValue());
        annotation.elements().forEach(element -> annotationValue(edges, origin, element.value()));
    }
    private static void annotationValue(Set<Edge> edges, String origin, AnnotationValue value) {
        if (value instanceof AnnotationValue.OfClass type) addTypes(edges, origin, type.className().stringValue());
        if (value instanceof AnnotationValue.OfEnum type) addTypes(edges, origin, type.className().stringValue());
        if (value instanceof AnnotationValue.OfAnnotation nested) annotation(edges, origin, nested.annotation());
        if (value instanceof AnnotationValue.OfArray values) values.values().forEach(item -> annotationValue(edges, origin, item));
    }

    private static void addTypes(Set<Edge> edges, String origin, String descriptor) {
        var matcher = INTERNAL_TYPE.matcher(descriptor);
        while (matcher.find()) edges.add(new Edge(origin, matcher.group().replace('/', '.'), false));
    }

    static Set<Edge> sourceEdges(String origin, String source, Set<String> knownClasses) {
        var edges = new HashSet<Edge>();
        String code = NON_CODE.matcher(source).replaceAll(" ");
        var matcher = QUALIFIED_TYPE.matcher(code);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (candidate.startsWith("com.zoutrankil.")) {
                while (!knownClasses.contains(candidate) && candidate.contains("."))
                    candidate = candidate.substring(0, candidate.lastIndexOf('.'));
                if (knownClasses.contains(candidate)) edges.add(new Edge(origin, candidate, false));
            } else {
                var parts = candidate.split("\\.");
                for (int index = 0; index < parts.length; index++) {
                    if (Character.isUpperCase(parts[index].charAt(0))) {
                        edges.add(new Edge(origin, String.join(".", Arrays.copyOf(parts, index + 1)), false));
                        break;
                    }
                }
            }
        }
        var imports = Pattern.compile("\\bimport\\s+(?!static\\b)([\\w.]+)(\\.\\*)?\\s*;").matcher(code);
        var explicit = new HashMap<String, String>();
        var wildcardPackages = new HashSet<String>();
        while (imports.find()) {
            String name = imports.group(1);
            if (imports.group(2) != null) wildcardPackages.add(name);
            else explicit.put(name.substring(name.lastIndexOf('.') + 1), name);
        }
        String usage = code.replaceAll("\\b(?:import|package)\\s+[^;]+;", " ");
        var unqualifiedNames = new HashSet<String>();
        var words = Pattern.compile("(?<![\\w$.])[A-Za-z_$][\\w$]*").matcher(usage);
        while (words.find()) unqualifiedNames.add(words.group());
        var localTypes = new HashSet<String>();
        var declarations = Pattern.compile("\\b(?:class|interface|record|enum)\\s+([A-Za-z_$][\\w$]*)").matcher(usage);
        while (declarations.find()) localTypes.add(declarations.group(1));
        String ownPackage = origin.substring(0, origin.lastIndexOf('.'));
        for (String type : knownClasses) {
            int dot = type.lastIndexOf('.');
            String simple = type.substring(dot + 1), typePackage = type.substring(0, dot);
            if (!wildcardPackages.contains(typePackage) || explicit.containsKey(simple)
                    || knownClasses.contains(ownPackage + "." + simple)
                    || localTypes.contains(simple)) continue;
            if (unqualifiedNames.contains(simple))
                edges.add(new Edge(origin, type, false));
        }
        return edges;
    }

    static SortedSet<Violation> violations(Collection<Edge> edges) {
        var result = new TreeSet<Violation>();
        for (var edge : edges) {
            String origin = topLevel(edge.origin()), target = topLevel(edge.target());
            if (origin.equals(target)) continue;
            if (origin.startsWith("com.zoutrankil.data.") && target.startsWith("com.zoutrankil.batch."))
                result.add(new Violation("data-to-batch", origin, target));
            if (role(origin, "domain") && (hasRole(target, "service", "application", "port", "repository", "storage", "client", "config", "cli", "web", "bootstrap")
                    || target.startsWith("org.springframework.") || storageAccess(target)))
                result.add(new Violation("domain-outward", origin, target));
            if (role(origin, "mapper") && (hasRole(target, "service", "application", "port", "repository", "storage", "config", "cli", "web", "bootstrap") || storageAccess(target)))
                result.add(new Violation("mapper-outward", origin, target));
            if (role(origin, "port")) {
                if (hasRole(target, "repository", "storage", "client", "config", "cli", "web", "bootstrap")
                        || target.startsWith("org.springframework.") || storageAccess(target)
                        || hasRole(target, "service", "application") && !PORT_CONTAINERS.contains(target))
                    result.add(new Violation("port-outward", origin, target));
                if (PORT_CONTAINERS.contains(target) && edge.member() && !edge.target().contains("$"))
                    result.add(new Violation("port-executes-runner", origin, target));
            }
            if (hasRole(origin, "repository", "storage") && hasRole(target, "service", "application")) {
                if (PORT_CONTAINERS.contains(target)) {
                    if (edge.member() && !edge.target().contains("$"))
                        result.add(new Violation("repository-executes-runner", origin, target));
                } else
                    result.add(new Violation("repository-to-application", origin, target));
            }
            if (hasRole(origin, "service", "application") && storageAccess(target))
                result.add(new Violation("application-storage", origin, target));
            if (hasRole(origin, "cli", "web") && (hasRole(target, "repository", "storage", "client") || storageAccess(target)))
                result.add(new Violation("entrypoint-infrastructure", origin, target));
        }
        return result;
    }

    private static boolean storageAccess(String target) {
        return target.startsWith("io.questdb.client.") || target.startsWith("javax.sql.")
                || target.startsWith("org.springframework.jdbc.core.")
                || target.startsWith("org.springframework.jdbc.datasource.")
                || target.startsWith("java.sql.") && JDBC_TYPES.contains(target.substring("java.sql.".length()));
    }
    private static boolean role(String type, String role) {
        return type.startsWith("com.zoutrankil.data.") && type.contains("." + role + ".");
    }
    private static boolean hasRole(String type, String... roles) {
        return Arrays.stream(roles).anyMatch(role -> role(type, role));
    }
    static String topLevel(String name) { return name.replace('/', '.').split("\\$", 2)[0]; }

    static Map<Violation, String> readExceptions(Path path) throws IOException {
        var result = new TreeMap<Violation, String>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 5 || !fields[3].matches("T(?:0[5-9]|1[0-8])") || fields[4].isBlank()
                    || !fields[1].matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")
                    || !fields[2].matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+"))
                throw new IllegalArgumentException("Exact dependency and remediation task required: " + line);
            var violation = new Violation(fields[0], fields[1], fields[2]);
            if (result.put(violation, fields[3] + ": " + fields[4]) != null)
                throw new IllegalArgumentException("Duplicate architecture exception: " + violation.key());
        }
        return result;
    }
}
