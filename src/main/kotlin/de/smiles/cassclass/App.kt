package de.smiles.cassclass

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.type.DataType
import com.datastax.oss.driver.api.core.type.ListType
import com.datastax.oss.driver.api.core.type.MapType
import com.datastax.oss.driver.api.core.type.SetType
import com.datastax.oss.driver.api.core.type.UserDefinedType
import com.datastax.oss.driver.api.mapper.annotations.CqlName
import com.datastax.oss.driver.api.mapper.annotations.Entity
import com.datastax.oss.driver.api.mapper.annotations.PropertyStrategy
import com.datastax.oss.protocol.internal.ProtocolConstants
import com.improve_future.case_changer.beginWithLowerCase
import com.improve_future.case_changer.toCamelCase
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

/**
 * @author Leon Camus
 * @since 13.12.2021
 */
fun main(args: Array<String>) {
    val parser = ArgParser("cass-class")

    val cqlDatabaseAddress by parser.option(ArgType.String, "address")
    val cqlDatabasePort by parser.option(ArgType.Int, "port")
    val cqlDatacenter by parser.option(ArgType.String, "datacenter")
    val cqlKeyspace by parser.argument(ArgType.String, "keyspace")

    val outputDir by parser.option(ArgType.String, "dir")
    val outputPackage by parser.option(ArgType.String, "package")

    parser.parse(args)

    val session = CqlSession.builder()
        .addContactPoint(InetSocketAddress(cqlDatabaseAddress ?: "localhost", cqlDatabasePort ?: 9042))
        .withLocalDatacenter(cqlDatacenter ?: "DC1")
        .withKeyspace(cqlKeyspace)
        .build()

    fun mapType(dataType: DataType): TypeName? = when (dataType.protocolCode) {
        ProtocolConstants.DataType.ASCII ->
            ClassName.bestGuess(String::class.qualifiedName!!)
        ProtocolConstants.DataType.VARCHAR ->
            ClassName.bestGuess(String::class.qualifiedName!!)
        ProtocolConstants.DataType.BIGINT ->
            ClassName.bestGuess(Long::class.qualifiedName!!)
        ProtocolConstants.DataType.VARINT ->
            ClassName.bestGuess(Long::class.qualifiedName!!)
        ProtocolConstants.DataType.BOOLEAN ->
            ClassName.bestGuess(Boolean::class.qualifiedName!!)
        ProtocolConstants.DataType.INT ->
            ClassName.bestGuess(Int::class.qualifiedName!!)
        ProtocolConstants.DataType.FLOAT ->
            ClassName.bestGuess(Float::class.qualifiedName!!)
        ProtocolConstants.DataType.DOUBLE ->
            ClassName.bestGuess(Double::class.qualifiedName!!)
        ProtocolConstants.DataType.UUID ->
            ClassName.bestGuess(UUID::class.qualifiedName!!)
        ProtocolConstants.DataType.TIMEUUID ->
            ClassName.bestGuess(UUID::class.qualifiedName!!)
        ProtocolConstants.DataType.TIME ->
            ClassName.bestGuess(Instant::class.qualifiedName!!)
        ProtocolConstants.DataType.DATE ->
            ClassName.bestGuess(Instant::class.qualifiedName!!)
        ProtocolConstants.DataType.TIMESTAMP ->
            ClassName.bestGuess(Instant::class.qualifiedName!!)
        ProtocolConstants.DataType.LIST ->
            ClassName.bestGuess(List::class.qualifiedName!!)
                .parameterizedBy(mapType((dataType as ListType).elementType)!!)
        ProtocolConstants.DataType.SET ->
            ClassName.bestGuess(Set::class.qualifiedName!!)
                .parameterizedBy(mapType((dataType as SetType).elementType)!!)
        ProtocolConstants.DataType.MAP ->
            ClassName.bestGuess(MapType::class.qualifiedName!!)
                .parameterizedBy(
                    mapType((dataType as MapType).keyType)!!,
                    mapType((dataType as MapType).valueType)!!
                )
        ProtocolConstants.DataType.UDT ->
            ClassName(
                outputPackage ?: "",
                (dataType as UserDefinedType).name.asInternal().toCamelCase())
        else -> null
    }

    val outFileDir: File = outputDir?.let { File(it) } ?: File("")
    val outPackageDir = File(outFileDir, outputPackage?.replace(".", "/") ?: "")
    outPackageDir.mkdirs()

    val keyspaceMeta = session.metadata.getKeyspace(cqlKeyspace).get()
    keyspaceMeta.userDefinedTypes.forEach { name, type ->
        val typeSpec = TypeSpec.classBuilder(name.asInternal().toCamelCase())
            .addModifiers(KModifier.DATA)
        val constructorSpec = FunSpec.constructorBuilder()

        typeSpec.addAnnotation(
            AnnotationSpec.builder(CqlName::class)
                .addMember("%S", name.asInternal())
                .build()
        ).addAnnotation(
            AnnotationSpec.builder(Entity::class)
                .addMember("defaultKeyspace = %S", cqlKeyspace)
                .build()
        ).addAnnotation(
            AnnotationSpec.builder(PropertyStrategy::class)
                .addMember("mutable = false")
                .build()
        )

        type.fieldNames.zip(type.fieldTypes).forEach { (fieldName, fieldType) ->
            val propertyName = fieldName.asInternal().toCamelCase().beginWithLowerCase()
            typeSpec.addProperty(
                PropertySpec.builder(propertyName, mapType(fieldType)!!)
                    .initializer(propertyName)
                    .addAnnotation(
                        AnnotationSpec.builder(CqlName::class)
                            .addMember("%S", fieldName.asInternal())
                            .build()
                    )
                    .build()
            )
            constructorSpec.addParameter(propertyName, mapType(fieldType)!!)
        }
        typeSpec.primaryConstructor(constructorSpec.build())

        val fileSpec = FileSpec.builder(outputPackage ?: "", name.asInternal().toCamelCase())
            .addType(typeSpec.build())
            .build()

        val outFile = File(outPackageDir, "${name.asInternal().toCamelCase()}.kt")
        val writer = outFile.writer()
        fileSpec.writeTo(writer)
        writer.close()
    }

    keyspaceMeta.tables.forEach { (name, type) ->
        val typeSpec = TypeSpec.classBuilder(name.asInternal().toCamelCase())
            .addModifiers(KModifier.DATA)
        val constructorSpec = FunSpec.constructorBuilder()

        typeSpec.addAnnotation(
            AnnotationSpec.builder(CqlName::class)
                .addMember("%S", name.asInternal())
                .build()
        ).addAnnotation(
            AnnotationSpec.builder(Entity::class)
                .addMember("defaultKeyspace = %S", cqlKeyspace)
                .build()
        ).addAnnotation(
            AnnotationSpec.builder(PropertyStrategy::class)
                .addMember("mutable = false")
                .build()
        )

        type.columns.forEach { (identifier, columnMetadata) ->
            val propertyName = identifier.asInternal().toCamelCase().beginWithLowerCase()
            typeSpec.addProperty(
                PropertySpec.builder(propertyName, mapType(columnMetadata.type)!!)
                    .initializer(propertyName)
                    .addAnnotation(
                        AnnotationSpec.builder(CqlName::class)
                            .addMember("%S", identifier.asInternal())
                            .build()
                    )
                    .build()
            )
            constructorSpec.addParameter(propertyName, mapType(columnMetadata.type)!!)
        }
        typeSpec.primaryConstructor(constructorSpec.build())

        val fileSpec = FileSpec.builder(outputPackage ?: "", name.asInternal().toCamelCase())
            .addType(typeSpec.build())
            .build()

        val outFile = File(outPackageDir, "${name.asInternal().toCamelCase()}.kt")
        val writer = outFile.writer()
        fileSpec.writeTo(writer)
        writer.close()
    }

    session.close()
}