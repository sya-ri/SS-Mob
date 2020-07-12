package me.syari.ss.mob.loader

import me.syari.ss.core.Main.Companion.console
import me.syari.ss.core.config.CreateConfig.config
import me.syari.ss.mob.Main.Companion.mobPlugin
import me.syari.ss.mob.data.MobData
import me.syari.ss.mob.data.event.MobSkillEvent
import me.syari.ss.mob.loader.exception.SkillFormatException
import java.io.File
import java.io.StringReader

object MobDataLoader {
    private const val FILE_EXTENSION = ".yml"

    fun loadMobData(directoryName: String): MutableSet<MobData> {
        var directory = mobPlugin.dataFolder
        if (!directory.exists()) directory.mkdir()
        directoryName.split("/".toRegex()).forEach { subDirectory ->
            directory = File(directory, subDirectory)
            if (!directory.exists()) directory.mkdir()
        }
        return mutableSetOf<MobData>().apply {
            directory.list()?.forEach { fileName ->
                if (fileName.endsWith(FILE_EXTENSION)) {
                    add(loadMobData(fileName, directory))
                }
            }
        }
    }

    private inline val String.withoutComment get() = replace("#.*".toRegex(), "")

    private inline val String.withoutBlankLines get() = lines().filter(String::isNotBlank)

    private inline val List<String>.withIndentWidth get() = map { it to it.indentWidth }

    private inline val String.indentWidth get() = indexOfFirst { !it.isWhitespace() }

    private sealed class StatementGroup {
        private val content = mutableListOf<StatementGroup>()

        fun addStatement(statement: String) {
            content.add(Statement(statement))
        }

        fun addSubGroup(
            parentGroup: SubGroup?,
            statement: String
        ): SubGroup {
            return SubGroup(parentGroup, statement).apply { content.add(this) }
        }

        fun get() = content.toList()

        data class Statement(val statement: String): StatementGroup()
        data class SubGroup(
            val parentGroup: SubGroup?,
            val statement: String
        ): StatementGroup()
    }

    private fun loadMobData(
        fileName: String,
        directory: File
    ): MobData {
        val file = File(directory, fileName)
        val id = fileName.substringBefore(FILE_EXTENSION)
        val (configContent, skillContent) = file.readText().split("skill:").let { it.getOrNull(0) to it.getOrNull(1) }
        val config = configContent?.let {
            config(mobPlugin, console, fileName, StringReader(configContent))
        }
        val skillLines = skillContent?.let { content ->
            val lines = content.withoutComment.withoutBlankLines.withIndentWidth
            val statementGroup = StatementGroup.SubGroup(null, "")
            val minIndentWidth = lines.firstOrNull()?.second
            if (minIndentWidth != null) {
                var lastDepth = 1
                var currentGroup = statementGroup
                lines.forEach { (statement, indentWidth) ->
                    val depth = indentWidth / minIndentWidth
                    if (depth < lastDepth) {
                        for (i in depth until lastDepth) {
                            currentGroup.parentGroup?.let {
                                currentGroup = it
                            } ?: throw SkillFormatException("インデントが不正です '$statement'")
                        }
                        lastDepth = depth
                    }
                    if (":\\s*\$".toRegex().find(statement) != null) {
                        if (depth == 1) {
                            val eventText = statement.replace("\\s+".toRegex(), "").removeSuffix(":")
                            if (MobSkillEvent.matchFirst(eventText) == null) throw SkillFormatException("イベントではありません '$eventText'")
                        }
                        if (lastDepth <= depth) {
                            currentGroup = currentGroup.addSubGroup(currentGroup, statement)
                            lastDepth++
                        } else {
                            println("Depth Error $lastDepth->$depth '$statement'")
                        }
                    } else if (currentGroup.parentGroup != null) {
                        currentGroup.addStatement(statement)
                    } else {
                        throw SkillFormatException("関数はイベント内に入れてください '$statement'")
                    }
                }
            }

            buildString {
                fun groupToString(
                    statementGroup: StatementGroup,
                    depth: Int
                ) {
                    statementGroup.get().forEach {
                        for (i in 0 until depth) {
                            append("  ")
                        }
                        when (it) {
                            is StatementGroup.Statement -> {
                                appendln(it.statement)
                            }
                            is StatementGroup.SubGroup -> {
                                appendln(it.statement)
                                groupToString(it, depth + 1)
                            }
                        }
                    }
                }

                groupToString(statementGroup, 0)
            }
        }
        mobPlugin.logger.info(
            """
            |$id
            |${config?.section("")}
            |$skillLines
            """.trimMargin()
        )
        return MobData(id)
    }
}