# IntelliJ-UML-generator
This tool generates both an editable UMLet class diagram (.uxf) and a PlantUML file (.puml) from Java source files. Both files contain the same types, members, and relationships.

The diagrams follow compact UML notation:

+ attribute: Type
+ operation(parameter: Type): ReturnType

Default multiplicities such as [1] are omitted. Abstract elements are displayed in italics, static elements are underlined, and interfaces are marked with <<interface>>. Inheritance and interface implementation are represented using distinct UML relationships.

Named inner classes, annotation types, enums, and records are supported.

Supertypes declared directly in extends or implements are preserved in the diagram, even if their Java source files are not included in the analyzed source path. The generator creates simplified external class or interface placeholders without inventing members.

For example, Mensch implements Comparable<Mensch> is represented with a realization relationship to Comparable<Mensch>. External supertypes referenced multiple times are merged into a single element.

Ordinary external attribute, parameter, and return types such as String, Object, or List do not generate additional class boxes.

Attribute types referencing other types included in the diagram also generate directed associations. Arrays and collection types receive a multiplicity of *, while Optional<T> receives 0..1.

This behavior can be disabled using --no-associations.

Requirements

A JDK 17 or newer is required.

A standalone JRE is not sufficient because the generator uses the Java parser included in the JDK.

Windows

The prebuilt umlet-java-generator.jar is already included.

The second argument specifies an optional diagram name:

generate-umlet.bat "C:\Path\to\Project\src\Blatt04" "ClassDiagram"

Alternatively, run the JAR directly:

java -jar umlet-java-generator.jar "C:\Path\to\Project\src\Blatt04" ClassDiagram

The generated files are always saved alongside the analyzed Java source files:

C:\Path\to\Project\src\Blatt04\ClassDiagram.uxf
C:\Path\to\Project\src\Blatt04\ClassDiagram.puml

If no diagram name is specified, the default filenames are diagramm.uxf and diagramm.puml.

When analyzing a single Java file, the output is saved in its parent directory. When analyzing a directory, the output is saved directly in that directory.

IntelliJ IDEA Integration (External Tool)

Navigate to:

File → Settings → Tools → External Tools

Create a new external tool with the following configuration:

Name: Java → UMLet
Program: $JDKPath$\bin\java.exe
Arguments: -jar "PATH\umlet-java-generator.jar" "$FileDir$" "ClassDiagram"
Working directory: $ProjectFileDir$

After configuring the tool, select a package or source directory in the Project view and execute:

Tools → External Tools → Java → UMLet

To analyze the entire source root instead of the selected directory, replace the first argument with:

"$ProjectFileDir$\src"

The generated .puml file can be previewed directly in IntelliJ IDEA using a PlantUML plugin.

The .uxf file remains available for editing in UMLet or UMLetino and can also be shared with others.

Command-Line Options

Option

Description

--no-package-box

Hide package boundaries

--no-associations

Disable associations derived from attribute types

--help

Display help information

The generator does not infer any meaning from directory or file names.

It recursively scans the provided path and analyzes every .java file based on its source code. Packages, type names, and relationships are derived exclusively from Java syntax.

Syntax errors cause the generator to exit with an error code, preventing the creation of potentially misleading diagram files.

Building from Source

If the source code has been modified, rebuild the generator.

Windows:

build.bat

Linux/macOS:

./build.sh
