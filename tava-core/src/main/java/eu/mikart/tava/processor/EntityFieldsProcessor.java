package eu.mikart.tava.processor;

import eu.mikart.tava.schema.annotation.Field;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.PrimitiveType;
import javax.tools.Diagnostic;

/** Generates typed field companions for records annotated with Tava's entity annotation. */
@SupportedAnnotationTypes("eu.mikart.tava.schema.annotation.Entity")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class EntityFieldsProcessor extends AbstractProcessor {
    /** Creates a field companion processor. */
    public EntityFieldsProcessor() {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (var annotation : annotations) {
            for (var element : round.getElementsAnnotatedWith(annotation)) {
                if (!(element instanceof TypeElement record) || record.getKind() != ElementKind.RECORD) continue;
                if (!record.getTypeParameters().isEmpty()
                        || record.getNestingKind().isNested()) {
                    processingEnv
                            .getMessager()
                            .printMessage(
                                    Diagnostic.Kind.WARNING,
                                    "Field companions require a top-level, non-generic record",
                                    record);
                    continue;
                }
                String packageName = processingEnv
                        .getElementUtils()
                        .getPackageOf(record)
                        .getQualifiedName()
                        .toString();
                String name = record.getSimpleName() + "Fields";
                String qualifiedName = packageName.isEmpty() ? name : packageName + "." + name;
                try (Writer writer = processingEnv
                        .getFiler()
                        .createSourceFile(qualifiedName, record)
                        .openWriter()) {
                    if (!packageName.isEmpty()) writer.write("package " + packageName + ";\n");
                    writer.write("/** Typed storage fields for " + record.getSimpleName() + ". */\n");
                    writer.write(
                            "@SuppressWarnings({\"unchecked\", \"rawtypes\"})\npublic final class " + name + " {\n");
                    writer.write("private " + name + "() {}\n");
                    for (var component : record.getRecordComponents()) {
                        var type = component.asType();
                        String javaType = type.getKind().isPrimitive()
                                ? processingEnv
                                        .getTypeUtils()
                                        .boxedClass((PrimitiveType) type)
                                        .getQualifiedName()
                                        .toString()
                                : type.toString();
                        String erased = type.getKind().isPrimitive()
                                ? javaType
                                : processingEnv.getTypeUtils().erasure(type).toString();
                        String fieldName = component.getSimpleName().toString();
                        var mapped = component.getAnnotation(Field.class);
                        String storageName = mapped == null || mapped.value().isEmpty() ? fieldName : mapped.value();
                        writer.write("/** Storage field for " + fieldName + ". */\n");
                        writer.write("public static final eu.mikart.tava.query.FieldRef<" + javaType + "> "
                                + fieldName + " = new eu.mikart.tava.query.FieldRef<>(\"" + escape(storageName)
                                + "\", (Class) " + erased + ".class);\n");
                    }
                    writer.write("}\n");
                } catch (IOException failure) {
                    processingEnv
                            .getMessager()
                            .printMessage(
                                    Diagnostic.Kind.ERROR,
                                    "Cannot generate field companion: " + failure.getMessage(),
                                    record);
                }
            }
        }
        return false;
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character == '\\' || character == '"') result.append('\\').append(character);
            else if (character < 32 || character == 127) result.append(String.format("\\%03o", (int) character));
            else result.append(character);
        }
        return result.toString();
    }
}
