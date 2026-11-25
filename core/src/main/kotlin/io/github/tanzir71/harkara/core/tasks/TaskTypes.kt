package io.github.tanzir71.harkara.core.tasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalizedText(val en: String, val bn: String) {
    fun forLanguage(language: String) = if (language == "bn") bn else en
}

@Serializable
data class TaskField(val key: String, val label: LocalizedText, val required: Boolean = false, val multiline: Boolean = false)

@Serializable
data class TaskType(val id: String, val label: LocalizedText, val fields: List<TaskField>)

/** Schema-driven task types (`data/task-types.json`, adapted from litegig). */
@Serializable
data class TaskTypes(val types: List<TaskType>) {
    fun byId(id: String): TaskType? = types.firstOrNull { it.id == id }

    /** Names of required fields left empty, in schema order. */
    fun missing(typeId: String, values: Map<String, String>): List<String> =
        byId(typeId)?.fields?.filter { it.required && values[it.key].isNullOrBlank() }?.map { it.key } ?: listOf("type")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TaskTypes = json.decodeFromString(serializer(), text)
    }
}
