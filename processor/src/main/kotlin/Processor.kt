package com.tschuchort.copyctor.processor

import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import me.eugeniomarletti.kotlin.metadata.*
import me.eugeniomarletti.kotlin.metadata.jvm.internalName
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver
import java.io.File
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.*
import javax.lang.model.type.NoType
import javax.lang.model.type.TypeMirror
import javax.tools.Diagnostic
@Target(AnnotationTarget.CLASS)
annotation class PartialCopyCtors

@Suppress("unused")
@AutoService(Processor::class)
internal class CopyCtorProcessor : AbstractProcessor() {
	companion object {
		const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
		const val GENERATE_KOTLIN_CODE_OPTION = "generate.kotlin.code"
		const val GENERATE_ERRORS_OPTION = "generate.error"
		const val FILE_SUFFIX_OPTION = "suffix"
		val annotationClass = PartialCopyCtors::class
	}

	private val kaptKotlinGeneratedDir by lazy { processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME] }
	private val generateErrors by lazy { processingEnv.options[GENERATE_ERRORS_OPTION] == "true" }
	private val generateKotlinCode by lazy { processingEnv.options[GENERATE_KOTLIN_CODE_OPTION] == "true" }
	private val generatedFilesSuffix by lazy { processingEnv.options[FILE_SUFFIX_OPTION] ?: "Generated"}

	private val typeUtils get() = processingEnv.typeUtils
	private val elemUtils get() = processingEnv.elementUtils

	override fun getSupportedAnnotationTypes() = setOf(annotationClass.java.name)
	override fun getSupportedOptions() = setOf(KAPT_KOTLIN_GENERATED_OPTION_NAME, GENERATE_KOTLIN_CODE_OPTION, GENERATE_ERRORS_OPTION)
	override fun getSupportedSourceVersion() = SourceVersion.latestSupported()!!

	override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
		for(annotatedElem in roundEnv.getElementsAnnotatedWith(annotationClass.java)) {
			val className = annotatedElem.simpleName.toString()
			val packageName = processingEnv.elementUtils.getPackageOf(annotatedElem).qualifiedName.toString()

			if(!annotatedElem.isDataClass())
				throw AnnotationProcessingException("PartialCopyCtors annotation is only available for data classes", annotatedElem)

			val annotatedTypeElem = annotatedElem as TypeElement

			if(annotatedTypeElem.constructors.isEmpty())
				throw AnnotationProcessingException(
						"can't generate copy function because the annotated type doesn't have a constructor",
						annotatedTypeElem)

			/*
			fun ProtoBuf.Type.extractFullName() = extractFullName(classData)
			val superTypes = classData.classProto.supertypeList*/

			val ctorParams = annotatedTypeElem.constructors[0].parameters

			// don't make a factory method for Object since that would be pointless
			val superClasses = getSuperClasses(annotatedTypeElem).filter { !it.simpleName.contentEquals("Object") }

			val superClassesWithOverridenMember = superClasses.filter { superClass ->
						ctorParams.any { ctorParam ->
							superClass.declaredGetters.any {
								it.matchesGetterFor(ctorParam) && !it.overridesAnything() && !it.modifiers.contains(Modifier.PRIVATE)
							}
						}
					}

			// only create the factory class if there is at least one method to be generated
			if(superClassesWithOverridenMember.isNotEmpty()) {
				val factoryClassName = "${className}Factory"

				val fileSpec = FileSpec.builder(packageName, factoryClassName)
						.addType(TypeSpec.classBuilder(factoryClassName)
								.addModifiers(KModifier.FINAL)
								.primaryConstructor(FunSpec.constructorBuilder()
										.addModifiers(KModifier.PRIVATE).build())
								.addType(TypeSpec.companionObjectBuilder()
										.addFunctions(
												superClassesWithOverridenMember.map { generateCopyFun(annotatedTypeElem, it) }
										).build()
								).build()
						).build()

				writeKotlinFile(fileSpec)
			}
		}

		return true
	}

	private fun generateCopyFun(annotatedType: TypeElement, superType: TypeElement): FunSpec {
		val (nameResolver, classProto) = (annotatedType.kotlinMetadata as KotlinClassMetadata).data
		val classData = (annotatedType.kotlinMetadata as KotlinClassMetadata).data

		classProto.getConstructor(0).valueParameterList
				.forEach { it.writeTo(System.out); log("${annotatedType.simpleName} param: ${it.type.extractFullName(classData)} " +
													   "${it.declaresDefaultValue}") }

		val ctorParams = annotatedType.constructors[0].parameters!!
		val ctorArgs = ctorParams.map { "${it.simpleName} = ${it.simpleName}" }.intersperse(", ").concat()

		/*val writer = StringWriter()
		elemUtils.printElements(writer, *ctorParams.toTypedArray())
		log("$writer")*/

		//(annotatedType.kotlinMetadata as KotlinClassMetadata).data.getValueParameterOrNull()

		return FunSpec.builder("copy")
				.addTypeVariables(annotatedType.typeParameters.map(TypeParameterElement::asTypeVariableName))
				.addAnnotation(JvmStatic::class.java)
				.addParameter(
						name = "from",
						type = superType.asTypeName()
				)
				.addParameters(ctorParams.map { ctorParam ->
					ParameterSpec.getAsBuilder(ctorParam)
							.apply {
								if(superType.declaredGetters.any { it.matchesGetterFor(ctorParam) })
									defaultValue("from.${ctorParam.simpleName}")
							}
							.build()
				})
				.addStatement("return %T($ctorArgs)", annotatedType)
				.returns(annotatedType.asTypeName())
				.build()
	}

	private fun getSuperClasses(typeElem: TypeElement): List<TypeElement> =
		if(typeElem.superclass !is NoType) {
			val superClassElem = typeUtils.asElement(typeElem.superclass) as TypeElement
			getSuperClasses(superClassElem) + superClassElem
		}
		else {
			emptyList()
		}

	private fun log(msg: String) = processingEnv.messager.printMessage(Diagnostic.Kind.NOTE, msg)

	private fun TypeMirror.asElement() = typeUtils.asElement(this)

	private fun writeKotlinFile(fileSpec: FileSpec, fileName: String = fileSpec.name, packageName: String = fileSpec.packageName) {
		val relativePath = packageName.replace('.', File.separatorChar)

		val outputFolder = File(kaptKotlinGeneratedDir, relativePath)
		outputFolder.mkdirs()

		// finally write to output file
		File(outputFolder, "$fileName$generatedFilesSuffix.kt").writeText(fileSpec.toString())
	}

	private fun ExecutableElement.overridesAnything(): Boolean {
		if(!isMethod())
			return false

		val declaringClass = enclosingElement as TypeElement

		if(declaringClass.superclass is NoType)
			return false

		val superClassMembers = elemUtils.getAllMembers(declaringClass.superclass.asElement() as TypeElement)
				.filter(Element::isMethod).castList<ExecutableElement>()

		return superClassMembers.any { elemUtils.overrides(this, it, declaringClass) }
	}

	val TypeElement.getters
		get() = elemUtils.getAllMembers(this).filter(Element::isGetter)

	val TypeElement.fields
		get() = elemUtils.getAllMembers(this).filter(Element::isField)
}

internal fun ProtoBuf.ValueParameter.nameAsString(nameResolver: NameResolver) = nameResolver.getString(name)

internal fun ExecutableElement.matchesGetterFor(variable: VariableElement)
		= simpleName.toString().isGetterNameFor(variable.simpleName.toString()) && returnType == variable.asType()

internal fun String.isGetterNameFor(fieldName: String)
		// due to how Kotlin translates field names into getter names "fieldName" and "FieldName" are the same
		= startsWith("get") && removePrefix("get").matches(Regex("($fieldName|${fieldName.capitalize()})"))



internal fun ParameterSpec.Companion.getAsBuilder(elem: VariableElement) = ParameterSpec.builder(
		name = elem.simpleName.toString(),
		type = elem.asType().asTypeName()
).jvmModifiers(elem.modifiers)

internal fun Element.isDataClass() = (kotlinMetadata as? KotlinClassMetadata)?.data?.classProto?.isDataClass ?: false

internal fun TypeElement.asTypeName() = asType().asTypeName()

internal val TypeElement.declaredFields
	get() = enclosedElements.filter(Element::isField).castList<VariableElement>()

internal val TypeElement.declaredGetters
	get() = enclosedElements.filter(Element::isGetter).castList<ExecutableElement>()

internal val TypeElement.constructors
	get() = enclosedElements.filter(Element::isConstructor).castList<ExecutableElement>()

internal fun Element.isConstructor() = kind == ElementKind.CONSTRUCTOR
internal fun Element.isMethod() = kind == ElementKind.METHOD
internal fun Element.isField() = kind.isField
internal fun Element.isGetter() = isMethod() && simpleName.startsWith("get")

class AnnotationProcessingException(message: String, val element: Element? = null) : RuntimeException(message) {
	override val message: String get() = super.message as String +
										 if(element != null) "\n element name: ${element.internalName}"
										 else ""
	operator fun component1() = message
	operator fun component2() = element
}
