package org.umletgen;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates equivalent UMLet/UMLetino UXF and PlantUML class diagrams from Java source code.
 * Parsing is based on the JDK compiler tree API and needs no external library.
 */
public final class UmletJavaGenerator {
    private static final int MIN_WIDTH = 260;
    private static final int MAX_WIDTH = 720;
    private static final int CHAR_WIDTH = 8;
    private static final int H_GAP = 90;
    private static final int PACKAGE_GAP = 110;
    private static final int V_GAP = 150;
    private static final int MARGIN = 55;
    private static final int LINE_HEIGHT = 22;

    private UmletJavaGenerator() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || hasFlag(args, "--help")) {
            usage();
            return;
        }

        Path source = Paths.get(args[0]).toAbsolutePath().normalize();
        String baseName = outputBaseName(args);
        boolean packageBoxes = !hasFlag(args, "--no-package-box");
        boolean associations = !hasFlag(args, "--no-associations");

        try {
            List<Path> files = collectJavaFiles(source);
            if (files.isEmpty()) {
                fail("Keine Dateien mit der Endung .java unter " + source + " gefunden.");
            }

            List<TypeModel> types = parse(files);
            if (types.isEmpty()) {
                fail("Keine Klassen, Interfaces, Annotationen, Enums oder Records gefunden.");
            }

            int parsedTypeCount = types.size();
            resolveRelations(types, associations);
            layout(types);
            String uxf = render(types, packageBoxes, associations);
            String plantUml = renderPlantUml(types, packageBoxes, associations);
            Path outputDirectory = Files.isDirectory(source) ? source : source.getParent();
            if (outputDirectory == null) outputDirectory = Paths.get(".").toAbsolutePath().normalize();
            Path uxfOutput = outputDirectory.resolve(baseName + ".uxf");
            Path pumlOutput = outputDirectory.resolve(baseName + ".puml");
            Files.writeString(uxfOutput, uxf, StandardCharsets.UTF_8);
            Files.writeString(pumlOutput, plantUml, StandardCharsets.UTF_8);

            System.out.println("UMLet-Diagramm erzeugt: " + uxfOutput);
            System.out.println("PlantUML-Diagramm erzeugt: " + pumlOutput);
            System.out.println("Enthaltene Typen: " + types.size()
                    + " (" + parsedTypeCount + " analysiert, "
                    + (types.size() - parsedTypeCount) + " externe Obertypen)");
        } catch (Exception exception) {
            System.err.println("Fehler: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                Java -> UMLet/UMLetino Generator

                Verwendung:
                  java -jar umlet-java-generator.jar <Quellpfad> [Diagrammname] [Optionen]

                Ausgabe:
                  <Quellordner>/diagramm.uxf und <Quellordner>/diagramm.puml
                  Bei einer einzelnen Java-Datei wird deren Elternordner verwendet.

                Externe Obertypen:
                  Direkt in extends oder implements genannte Typen werden auch dann als
                  reduzierte Platzhalter dargestellt, wenn ihr Quellcode nicht eingelesen wurde.

                Optionen:
                  --no-package-box   Paketrahmen ausblenden
                  --no-associations  Aus Attributtypen abgeleitete Assoziationen ausblenden
                  --help             Diese Hilfe anzeigen

                Beispiel:
                  java -jar umlet-java-generator.jar src MeinDiagramm
                """);
    }

    private static String outputBaseName(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") && !args[i].isBlank()) {
                String fileName = Paths.get(args[i]).getFileName().toString();
                String baseName = fileName.replaceFirst("(?i)\\.(uxf|puml)$", "");
                return baseName.isBlank() ? "diagramm" : baseName;
            }
        }
        return "diagramm";
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    private static List<Path> collectJavaFiles(Path source) throws IOException {
        if (Files.isRegularFile(source)) {
            return source.toString().toLowerCase(Locale.ROOT).endsWith(".java")
                    ? List.of(source) : List.of();
        }
        if (!Files.isDirectory(source)) {
            fail("Quellpfad existiert nicht: " + source);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static List<TypeModel> parse(List<Path> files) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("Ein vollständiges JDK ist erforderlich; eine reine JRE genügt nicht.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<TypeModel> result = new ArrayList<>();
        try (StandardJavaFileManager manager =
                     compiler.getStandardFileManager(diagnostics, Locale.GERMAN, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> fileObjects = manager.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics, List.of("-proc:none"), null, fileObjects);

            List<CompilationUnitTree> units = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) units.add(unit);

            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .toList();
            if (!errors.isEmpty()) {
                String details = errors.stream().limit(6)
                        .map(UmletJavaGenerator::formatDiagnostic)
                        .collect(Collectors.joining(System.lineSeparator()));
                fail("Der Java-Quellcode enthält Syntaxfehler. Es wurde kein Diagramm erzeugt:"
                        + System.lineSeparator() + details);
            }

            for (CompilationUnitTree unit : units) {
                SourceContext context = sourceContext(unit);
                for (Tree declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree classTree) {
                        readType(context, classTree, null, null, result);
                    }
                }
            }
        }
        return result;
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        String file = diagnostic.getSource() == null
                ? "<unbekannt>" : Paths.get(diagnostic.getSource().toUri()).getFileName().toString();
        return file + ":" + diagnostic.getLineNumber() + ": "
                + diagnostic.getMessage(Locale.GERMAN);
    }

    private static SourceContext sourceContext(CompilationUnitTree unit) {
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        Map<String, String> explicitImports = new HashMap<>();
        List<String> wildcardImports = new ArrayList<>();
        for (ImportTree imported : unit.getImports()) {
            if (imported.isStatic()) continue;
            String name = imported.getQualifiedIdentifier().toString();
            if (name.endsWith(".*")) {
                wildcardImports.add(name.substring(0, name.length() - 2));
            } else {
                explicitImports.put(simpleName(name), name);
            }
        }
        return new SourceContext(packageName, explicitImports, wildcardImports);
    }

    private static void readType(SourceContext context, ClassTree tree, String enclosingFullName,
                                 String enclosingDisplayName, List<TypeModel> target) {
        TypeKind kind = switch (tree.getKind()) {
            case INTERFACE -> TypeKind.INTERFACE;
            case ANNOTATION_TYPE -> TypeKind.ANNOTATION;
            case ENUM -> TypeKind.ENUM;
            case RECORD -> TypeKind.RECORD;
            default -> TypeKind.CLASS;
        };

        String ownName = tree.getSimpleName().toString();
        String fullName;
        String displayName;
        if (enclosingFullName == null) {
            fullName = qualify(context.packageName, ownName);
            displayName = ownName;
        } else {
            fullName = enclosingFullName + "." + ownName;
            displayName = enclosingDisplayName + "." + ownName;
        }

        TypeModel model = new TypeModel(context, fullName, displayName, ownName, kind,
                tree.getModifiers().getFlags().contains(Modifier.ABSTRACT), false);
        target.add(model);

        if (tree.getExtendsClause() != null) {
            model.rawParents.add(new RawParent(tree.getExtendsClause().toString(), false));
        }
        tree.getImplementsClause().forEach(implemented ->
                model.rawParents.add(new RawParent(implemented.toString(), kind == TypeKind.CLASS
                        || kind == TypeKind.RECORD || kind == TypeKind.ENUM)));

        // In javac's tree API, an interface's extended interfaces are returned by
        // getImplementsClause; for those the relationship is generalization, not realization.
        if (kind == TypeKind.INTERFACE || kind == TypeKind.ANNOTATION) {
            model.rawParents.replaceAll(parent -> new RawParent(parent.rawType, false));
        }

        for (Tree member : tree.getMembers()) {
            if (member instanceof ClassTree nested) {
                readType(context, nested, fullName, displayName, target);
            } else if (member instanceof VariableTree variable) {
                if (isEnumConstant(variable, tree, kind)) {
                    model.enumConstants.add(variable.getName().toString());
                } else {
                    model.fields.add(readField(variable, kind == TypeKind.INTERFACE
                            || kind == TypeKind.ANNOTATION));
                }
            } else if (member instanceof MethodTree method) {
                model.methods.add(readMethod(method, ownName,
                        kind == TypeKind.INTERFACE || kind == TypeKind.ANNOTATION));
            }
        }
    }

    private static boolean isEnumConstant(VariableTree variable, ClassTree owner, TypeKind kind) {
        return kind == TypeKind.ENUM
                && variable.getInitializer() instanceof NewClassTree
                && variable.getType() != null
                && variable.getType().toString().equals(owner.getSimpleName().toString());
    }

    private static FieldModel readField(VariableTree field, boolean publicByDefault) {
        Set<Modifier> flags = field.getModifiers().getFlags();
        String type = field.getType() == null ? "?" : field.getType().toString();
        String initializer = field.getInitializer() == null ? null : field.getInitializer().toString();
        return new FieldModel(visibility(flags, publicByDefault), field.getName().toString(), type,
                publicByDefault || flags.contains(Modifier.STATIC),
                publicByDefault || flags.contains(Modifier.FINAL), initializer);
    }

    private static MethodModel readMethod(MethodTree method, String ownName,
                                          boolean publicByDefault) {
        boolean constructor = method.getName().contentEquals("<init>");
        Set<Modifier> flags = method.getModifiers().getFlags();
        String parameters = method.getParameters().stream()
                .map(parameter -> parameter.getName() + ": " + parameter.getType())
                .collect(Collectors.joining(", "));
        return new MethodModel(
                visibility(flags, publicByDefault),
                constructor ? ownName : method.getName().toString(),
                parameters,
                constructor || method.getReturnType() == null ? null : method.getReturnType().toString(),
                flags.contains(Modifier.STATIC),
                flags.contains(Modifier.ABSTRACT));
    }

    private static String visibility(Set<Modifier> flags, boolean publicByDefault) {
        if (publicByDefault || flags.contains(Modifier.PUBLIC)) return "+";
        if (flags.contains(Modifier.PROTECTED)) return "#";
        if (flags.contains(Modifier.PRIVATE)) return "-";
        return "~";
    }

    private static void resolveRelations(List<TypeModel> types, boolean associations) {
        TypeIndex index = new TypeIndex(types);
        List<TypeModel> parsedTypes = List.copyOf(types);
        Map<String, TypeModel> externalParents = new LinkedHashMap<>();
        for (TypeModel type : parsedTypes) {
            for (RawParent parent : type.rawParents) {
                TypeModel resolved = index.resolve(parent.rawType, type);
                if (resolved == null) {
                    boolean interfaceType = parent.realization
                            || type.kind == TypeKind.INTERFACE
                            || type.kind == TypeKind.ANNOTATION;
                    String key = externalTypeKey(parent.rawType, type, interfaceType);
                    resolved = externalParents.computeIfAbsent(key,
                            ignored -> externalType(parent.rawType, type, interfaceType, key));
                }
                if (resolved != null && resolved != type) {
                    type.parents.add(new ParentLink(resolved, parent.realization));
                }
            }
            if (associations) {
                for (FieldModel field : type.fields) {
                    AssociationCandidate candidate = associationCandidate(field.type);
                    TypeModel resolved = index.resolve(candidate.elementType, type);
                    if (resolved != null && resolved != type) {
                        type.associations.add(new AssociationLink(
                                resolved, field.name, candidate.multiplicity));
                    }
                }
            }
        }
        types.addAll(externalParents.values());
    }

    private static String externalTypeKey(String rawType, TypeModel owner,
                                          boolean interfaceType) {
        String base = rawBaseType(rawType);
        String qualifiedBase = qualifyExternalBase(base, owner.context);
        String signature = qualifiedBase + typeArgumentsSuffix(rawType);
        return (interfaceType ? "interface:" : "class:") + signature;
    }

    private static String qualifyExternalBase(String base, SourceContext context) {
        if (base.isBlank() || base.contains(".")) return base;
        String imported = context.explicitImports.get(base);
        if (imported != null) return imported;
        if (isImplicitJavaLangType(base)) return "java.lang." + base;
        if (context.wildcardImports.size() == 1) {
            return context.wildcardImports.get(0) + "." + base;
        }
        return qualify(context.packageName, base);
    }

    private static boolean isImplicitJavaLangType(String type) {
        return Set.of("Appendable", "AutoCloseable", "CharSequence", "Cloneable",
                "Comparable", "Iterable", "Readable", "Runnable",
                "Thread.UncaughtExceptionHandler").contains(type);
    }

    private static String typeArgumentsSuffix(String rawType) {
        int generic = rawType.indexOf('<');
        return generic < 0 ? "" : rawType.substring(generic).trim();
    }

    private static TypeModel externalType(String rawType, TypeModel owner,
                                          boolean interfaceType, String key) {
        String base = rawBaseType(rawType);
        String displayName = simpleName(base) + typeArgumentsSuffix(rawType);
        return new TypeModel(
                new SourceContext("", Map.of(), List.of()),
                key,
                displayName,
                simpleName(base),
                interfaceType ? TypeKind.INTERFACE : TypeKind.CLASS,
                false,
                true);
    }

    private static AssociationCandidate associationCandidate(String rawType) {
        String type = rawType.trim();
        if (type.endsWith("[]") || type.endsWith("...")) {
            return new AssociationCandidate(type.replaceFirst("(\\[\\]|\\.\\.\\.)$", ""), "*");
        }
        String raw = rawBaseType(type);
        List<String> arguments = genericArguments(type);
        String simpleRaw = simpleName(raw);
        if (!arguments.isEmpty()) {
            if (simpleRaw.equals("Map") && arguments.size() > 1) {
                return new AssociationCandidate(arguments.get(1), "*");
            }
            if (Set.of("List", "Set", "Collection", "Iterable", "Stream", "Queue",
                    "Deque").contains(simpleRaw)) {
                return new AssociationCandidate(arguments.get(0), "*");
            }
            if (simpleRaw.equals("Optional")) {
                return new AssociationCandidate(arguments.get(0), "0..1");
            }
        }
        return new AssociationCandidate(type, "1");
    }

    private static List<String> genericArguments(String type) {
        int start = type.indexOf('<');
        int end = type.lastIndexOf('>');
        if (start < 0 || end <= start) return List.of();
        String body = type.substring(start + 1, end);
        List<String> result = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(body.substring(from, i).trim());
                from = i + 1;
            }
        }
        result.add(body.substring(from).trim());
        return result;
    }

    private static String rawBaseType(String type) {
        String cleaned = type.replaceAll("@[\\w.]+(?:\\([^)]*\\))?\\s*", "")
                .replace("? extends ", "").replace("? super ", "").trim();
        int generic = cleaned.indexOf('<');
        if (generic >= 0) cleaned = cleaned.substring(0, generic);
        return cleaned.replace("[]", "").replace("...", "").trim();
    }

    private static void layout(List<TypeModel> types) {
        Map<TypeModel, Integer> depths = new HashMap<>();
        for (TypeModel type : types) determineDepth(type, depths, new HashSet<>());
        types.forEach(type -> {
            type.depth = depths.get(type);
            type.width = calculateWidth(type);
            type.height = calculateHeight(type);
        });

        Map<Integer, Integer> levelY = new HashMap<>();
        int y = MARGIN;
        int maxDepth = types.stream().mapToInt(type -> type.depth).max().orElse(0);
        for (int depth = 0; depth <= maxDepth; depth++) {
            levelY.put(depth, y);
            int currentDepth = depth;
            int maxHeight = types.stream().filter(type -> type.depth == currentDepth)
                    .mapToInt(type -> type.height).max().orElse(90);
            y += maxHeight + V_GAP;
        }

        Map<String, List<TypeModel>> packages = types.stream()
                .collect(Collectors.groupingBy(type -> type.context.packageName,
                        LinkedHashMap::new, Collectors.toList()));
        List<String> packageNames = new ArrayList<>(packages.keySet());
        packageNames.sort(String::compareTo);

        int packageX = MARGIN;
        for (String packageName : packageNames) {
            List<TypeModel> packageTypes = packages.get(packageName);
            Map<Integer, List<TypeModel>> byDepth = packageTypes.stream()
                    .collect(Collectors.groupingBy(type -> type.depth));
            int packageWidth = byDepth.values().stream()
                    .mapToInt(UmletJavaGenerator::rowWidth).max().orElse(MIN_WIDTH);

            for (Map.Entry<Integer, List<TypeModel>> entry : byDepth.entrySet()) {
                List<TypeModel> row = entry.getValue();
                row.sort(Comparator.comparing(type -> type.displayName));
                int rowWidth = rowWidth(row);
                int x = packageX + (packageWidth - rowWidth) / 2;
                for (TypeModel type : row) {
                    type.x = x;
                    type.y = levelY.get(type.depth);
                    x += type.width + H_GAP;
                }
            }
            packageX += packageWidth + PACKAGE_GAP;
        }
    }

    private static int rowWidth(List<TypeModel> row) {
        return row.stream().mapToInt(type -> type.width).sum()
                + Math.max(0, row.size() - 1) * H_GAP;
    }

    private static int determineDepth(TypeModel type, Map<TypeModel, Integer> cache,
                                      Set<TypeModel> path) {
        if (cache.containsKey(type)) return cache.get(type);
        if (!path.add(type)) return 0;
        int depth = 0;
        for (ParentLink parent : type.parents) {
            depth = Math.max(depth, determineDepth(parent.target, cache, path) + 1);
        }
        path.remove(type);
        cache.put(type, depth);
        return depth;
    }

    private static int calculateWidth(TypeModel type) {
        int longest = renderLines(type).stream()
                .map(line -> line.replaceAll("[/_]", ""))
                .mapToInt(String::length).max().orElse(20);
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, 45 + longest * CHAR_WIDTH));
    }

    private static int calculateHeight(TypeModel type) {
        return Math.max(90, 26 + renderLines(type).size() * LINE_HEIGHT);
    }

    private static String render(List<TypeModel> types, boolean packageBoxes,
                                 boolean associations) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        xml.append("<diagram program=\"umlet\" version=\"15.1\">\n");
        xml.append("  <zoom_level>10</zoom_level>\n");
        if (packageBoxes) renderPackageBoxes(xml, types);
        types.forEach(type -> renderClass(xml, type));
        types.forEach(type -> type.parents.forEach(parent ->
                renderInheritance(xml, parent.target, type, parent.realization)));
        if (associations) {
            Set<String> rendered = new LinkedHashSet<>();
            Map<String, Integer> totals = new HashMap<>();
            Map<String, Integer> positions = new HashMap<>();
            for (TypeModel owner : types) {
                for (AssociationLink association : owner.associations) {
                    String pair = owner.fullName + "|" + association.target.fullName;
                    totals.merge(pair, 1, Integer::sum);
                }
            }
            for (TypeModel owner : types) {
                for (AssociationLink association : owner.associations) {
                    String key = owner.fullName + "|" + association.target.fullName + "|"
                            + association.role;
                    if (rendered.add(key)) {
                        String pair = owner.fullName + "|" + association.target.fullName;
                        int position = positions.getOrDefault(pair, 0);
                        positions.put(pair, position + 1);
                        renderAssociation(xml, owner, association, position, totals.get(pair));
                    }
                }
            }
        }
        xml.append("</diagram>\n");
        return xml.toString();
    }

    private static String renderPlantUml(List<TypeModel> types, boolean packageBoxes,
                                         boolean associations) {
        StringBuilder plant = new StringBuilder();
        plant.append("@startuml\n");
        plant.append("skinparam classAttributeIconSize 0\n");
        plant.append("top to bottom direction\n\n");

        Map<TypeModel, String> aliases = new LinkedHashMap<>();
        for (int index = 0; index < types.size(); index++) {
            aliases.put(types.get(index), "T" + index);
        }

        if (packageBoxes) {
            Map<String, List<TypeModel>> packages = types.stream()
                    .collect(Collectors.groupingBy(type -> type.context.packageName,
                            LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<TypeModel>> entry : packages.entrySet()) {
                if (entry.getKey().isBlank()) {
                    for (TypeModel type : entry.getValue()) {
                        appendPlantType(plant, type, aliases.get(type), "");
                    }
                } else {
                    plant.append("package \"").append(escapePlant(entry.getKey())).append("\" {\n");
                    for (TypeModel type : entry.getValue()) {
                        appendPlantType(plant, type, aliases.get(type), "  ");
                    }
                    plant.append("}\n\n");
                }
            }
        } else {
            for (TypeModel type : types) appendPlantType(plant, type, aliases.get(type), "");
        }

        for (TypeModel child : types) {
            for (ParentLink parent : child.parents) {
                plant.append(aliases.get(parent.target))
                        .append(parent.realization ? " <|.. " : " <|-- ")
                        .append(aliases.get(child)).append("\n");
            }
        }
        if (associations) {
            Set<String> rendered = new LinkedHashSet<>();
            for (TypeModel owner : types) {
                for (AssociationLink association : owner.associations) {
                    String key = owner.fullName + "|" + association.target.fullName + "|"
                            + association.role;
                    if (rendered.add(key)) {
                        plant.append(aliases.get(owner)).append(" \"1\" --> \"")
                                .append(escapePlant(association.multiplicity)).append("\" ")
                                .append(aliases.get(association.target)).append(" : ")
                                .append(escapePlant(association.role)).append("\n");
                    }
                }
            }
        }
        plant.append("@enduml\n");
        return plant.toString();
    }

    private static void appendPlantType(StringBuilder plant, TypeModel type, String alias,
                                        String indent) {
        String keyword = switch (type.kind) {
            case CLASS -> type.abstractType ? "abstract class" : "class";
            case INTERFACE -> "interface";
            case ANNOTATION -> "annotation";
            case ENUM -> "enum";
            case RECORD -> "record";
        };
        plant.append(indent).append(keyword).append(" \"")
                .append(escapePlant(type.displayName)).append("\" as ").append(alias);
        if (type.external) {
            plant.append("\n");
            return;
        }
        plant.append(" {\n");
        for (String constant : type.enumConstants) {
            plant.append(indent).append("  ").append(escapePlant(constant)).append("\n");
        }
        for (FieldModel field : type.fields) {
            plant.append(indent).append("  ").append(formatPlantField(field)).append("\n");
        }
        for (MethodModel method : type.methods) {
            plant.append(indent).append("  ").append(formatPlantMethod(method)).append("\n");
        }
        plant.append(indent).append("}\n");
    }

    private static String formatPlantField(FieldModel field) {
        StringBuilder value = new StringBuilder();
        value.append(field.visibility).append(" ").append(field.name).append(": ")
                .append(field.type);
        if (field.initializer != null && field.isFinal) {
            value.append(" = ").append(shorten(field.initializer, 55));
        }
        return (field.isStatic ? "{static} " : "") + escapePlantMember(value.toString());
    }

    private static String formatPlantMethod(MethodModel method) {
        StringBuilder value = new StringBuilder();
        value.append(method.visibility).append(" ").append(method.name)
                .append("(").append(method.parameters).append(")");
        if (method.returnType != null) value.append(": ").append(method.returnType);
        String modifiers = (method.isStatic ? "{static} " : "")
                + (method.isAbstract ? "{abstract} " : "");
        return modifiers + escapePlantMember(value.toString());
    }

    private static String escapePlant(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapePlantMember(String text) {
        return text.replace("\\", "\\\\")
                .replace("{", "\\{").replace("}", "\\}");
    }

    private static void renderPackageBoxes(StringBuilder xml, List<TypeModel> types) {
        Map<String, List<TypeModel>> packages = types.stream()
                .filter(type -> !type.context.packageName.isBlank())
                .collect(Collectors.groupingBy(type -> type.context.packageName,
                        LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<TypeModel>> entry : packages.entrySet()) {
            int minX = entry.getValue().stream().mapToInt(type -> type.x).min().orElse(MARGIN) - 28;
            int minY = entry.getValue().stream().mapToInt(type -> type.y).min().orElse(MARGIN) - 38;
            int maxX = entry.getValue().stream().mapToInt(type -> type.x + type.width)
                    .max().orElse(0) + 28;
            int maxY = entry.getValue().stream().mapToInt(type -> type.y + type.height)
                    .max().orElse(0) + 28;
            appendElement(xml, "UMLPackage", minX, minY, maxX - minX, maxY - minY,
                    "layer=-1\n" + entry.getKey(), "");
        }
    }

    private static void renderClass(StringBuilder xml, TypeModel type) {
        appendElement(xml, "UMLClass", type.x, type.y, type.width, type.height,
                String.join("\n", renderLines(type)), "");
    }

    private static List<String> renderLines(TypeModel type) {
        List<String> lines = new ArrayList<>();
        switch (type.kind) {
            case INTERFACE -> {
                lines.add("<<interface>>");
                lines.add(type.displayName);
            }
            case ANNOTATION -> {
                lines.add("<<annotation>>");
                lines.add(type.displayName);
            }
            case ENUM -> {
                lines.add("<<enumeration>>");
                lines.add(type.displayName);
            }
            case RECORD -> {
                lines.add("<<record>>");
                lines.add(type.displayName);
            }
            case CLASS -> lines.add(type.abstractType
                    ? "/" + type.displayName + "/" : type.displayName);
        }
        if (type.external) return lines;
        lines.add("--");
        lines.addAll(type.enumConstants);
        type.fields.forEach(field -> lines.add(formatField(field)));
        if ((!type.fields.isEmpty() || !type.enumConstants.isEmpty()) && !type.methods.isEmpty()) {
            lines.add("--");
        }
        type.methods.forEach(method -> lines.add(formatMethod(method)));
        return lines;
    }

    private static String formatField(FieldModel field) {
        String value = field.visibility + " " + field.name + ": " + field.type;
        if (field.initializer != null && field.isFinal) {
            value += " = " + shorten(field.initializer, 55);
        }
        return field.isStatic ? "_" + value + "_" : value;
    }

    private static String formatMethod(MethodModel method) {
        String value = method.visibility + " " + method.name + "(" + method.parameters + ")";
        if (method.returnType != null) value += ": " + method.returnType;
        if (method.isStatic) value = "_" + value + "_";
        if (method.isAbstract) value = "/" + value + "/";
        return value;
    }

    private static String shorten(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private static void renderInheritance(StringBuilder xml, TypeModel parent,
                                          TypeModel child, boolean realization) {
        Point start = new Point(parent.x + parent.width / 2, parent.y + parent.height);
        Point end = new Point(child.x + child.width / 2, child.y);
        renderRelation(xml, start, end, realization ? "lt=<<." : "lt=<<-", "");
    }

    private static void renderAssociation(StringBuilder xml, TypeModel owner,
                                          AssociationLink association, int position, int total) {
        TypeModel target = association.target;
        Point start;
        Point end;
        if (owner.x + owner.width <= target.x) {
            start = new Point(owner.x + owner.width, distributed(owner.y, owner.height, position, total));
            end = new Point(target.x, distributed(target.y, target.height, position, total));
        } else if (target.x + target.width <= owner.x) {
            start = new Point(owner.x, distributed(owner.y, owner.height, position, total));
            end = new Point(target.x + target.width,
                    distributed(target.y, target.height, position, total));
        } else if (owner.y <= target.y) {
            start = new Point(distributed(owner.x, owner.width, position, total),
                    owner.y + owner.height);
            end = new Point(distributed(target.x, target.width, position, total), target.y);
        } else {
            start = new Point(distributed(owner.x, owner.width, position, total), owner.y);
            end = new Point(distributed(target.x, target.width, position, total),
                    target.y + target.height);
        }
        String panel = "lt=->\nm1=1\nm2=" + association.multiplicity + "\n" + association.role;
        renderRelation(xml, start, end, panel, association.role);
    }

    private static int distributed(int origin, int size, int position, int total) {
        return origin + (position + 1) * size / (total + 1);
    }

    private static void renderRelation(StringBuilder xml, Point start, Point end,
                                       String panel, String ignoredLabel) {
        int x = Math.min(start.x, end.x) - 10;
        int y = Math.min(start.y, end.y) - 10;
        int width = Math.max(20, Math.abs(end.x - start.x) + 20);
        int height = Math.max(20, Math.abs(end.y - start.y) + 20);
        String points = (start.x - x) + ";" + (start.y - y) + ";"
                + (end.x - x) + ";" + (end.y - y);
        appendElement(xml, "Relation", x, y, width, height, panel, points);
    }

    private static void appendElement(StringBuilder xml, String id, int x, int y,
                                      int width, int height, String panel, String additional) {
        xml.append("  <element>\n");
        xml.append("    <id>").append(id).append("</id>\n");
        xml.append("    <coordinates>\n");
        xml.append("      <x>").append(x).append("</x>\n");
        xml.append("      <y>").append(y).append("</y>\n");
        xml.append("      <w>").append(width).append("</w>\n");
        xml.append("      <h>").append(height).append("</h>\n");
        xml.append("    </coordinates>\n");
        xml.append("    <panel_attributes>").append(escapeXml(panel))
                .append("</panel_attributes>\n");
        if (additional.isBlank()) xml.append("    <additional_attributes/>\n");
        else xml.append("    <additional_attributes>").append(escapeXml(additional))
                    .append("</additional_attributes>\n");
        xml.append("  </element>\n");
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String qualify(String packageName, String typeName) {
        return packageName.isBlank() ? typeName : packageName + "." + typeName;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }

    private enum TypeKind {
        CLASS, INTERFACE, ANNOTATION, ENUM, RECORD
    }

    private record SourceContext(String packageName, Map<String, String> explicitImports,
                                 List<String> wildcardImports) {
    }

    private static final class TypeModel {
        private final SourceContext context;
        private final String fullName;
        private final String displayName;
        private final String simpleName;
        private final TypeKind kind;
        private final boolean abstractType;
        private final boolean external;
        private final List<FieldModel> fields = new ArrayList<>();
        private final List<MethodModel> methods = new ArrayList<>();
        private final List<String> enumConstants = new ArrayList<>();
        private final List<RawParent> rawParents = new ArrayList<>();
        private final List<ParentLink> parents = new ArrayList<>();
        private final List<AssociationLink> associations = new ArrayList<>();
        private int depth;
        private int x;
        private int y;
        private int width;
        private int height;

        private TypeModel(SourceContext context, String fullName, String displayName,
                          String simpleName, TypeKind kind, boolean abstractType,
                          boolean external) {
            this.context = context;
            this.fullName = fullName;
            this.displayName = displayName;
            this.simpleName = simpleName;
            this.kind = kind;
            this.abstractType = abstractType;
            this.external = external;
        }
    }

    private static final class TypeIndex {
        private final Map<String, TypeModel> byFullName;
        private final Map<String, List<TypeModel>> bySimpleName;

        private TypeIndex(List<TypeModel> types) {
            byFullName = types.stream().collect(Collectors.toMap(
                    type -> type.fullName, type -> type, (first, ignored) -> first));
            bySimpleName = types.stream().collect(Collectors.groupingBy(type -> type.simpleName));
        }

        private TypeModel resolve(String rawType, TypeModel owner) {
            String base = rawBaseType(rawType);
            if (base.isBlank() || isPrimitiveOrStandard(base)) return null;
            TypeModel exact = byFullName.get(base);
            if (exact != null) return exact;

            String simple = simpleName(base);
            String imported = owner.context.explicitImports.get(simple);
            if (imported != null && byFullName.containsKey(imported)) return byFullName.get(imported);

            String samePackage = qualify(owner.context.packageName, base);
            if (byFullName.containsKey(samePackage)) return byFullName.get(samePackage);

            String enclosing = owner.fullName;
            while (enclosing.contains(".")) {
                enclosing = enclosing.substring(0, enclosing.lastIndexOf('.'));
                String nested = enclosing + "." + simple;
                if (byFullName.containsKey(nested)) return byFullName.get(nested);
            }

            for (String wildcard : owner.context.wildcardImports) {
                String candidate = wildcard + "." + simple;
                if (byFullName.containsKey(candidate)) return byFullName.get(candidate);
            }

            List<TypeModel> matches = bySimpleName.getOrDefault(simple, List.of());
            return matches.size() == 1 ? matches.get(0) : null;
        }

        private boolean isPrimitiveOrStandard(String type) {
            return Set.of("byte", "short", "int", "long", "float", "double", "boolean",
                    "char", "void", "String", "Object", "Integer", "Long", "Double",
                    "Float", "Boolean", "Character", "Byte", "Short").contains(simpleName(type));
        }
    }

    private record FieldModel(String visibility, String name, String type,
                              boolean isStatic, boolean isFinal, String initializer) {
    }

    private record MethodModel(String visibility, String name, String parameters,
                               String returnType, boolean isStatic, boolean isAbstract) {
    }

    private record RawParent(String rawType, boolean realization) {
    }

    private record ParentLink(TypeModel target, boolean realization) {
    }

    private record AssociationCandidate(String elementType, String multiplicity) {
    }

    private record AssociationLink(TypeModel target, String role, String multiplicity) {
    }

    private record Point(int x, int y) {
    }
}
