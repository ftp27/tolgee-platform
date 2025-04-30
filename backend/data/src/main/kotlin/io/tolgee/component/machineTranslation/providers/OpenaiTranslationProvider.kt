package io.tolgee.component.machineTranslation.providers

import io.tolgee.component.machineTranslation.MtValueProvider
import io.tolgee.configuration.tolgee.machineTranslation.OpenaiMachineTranslationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class OpenaiTranslationProvider(
  private val openaiMachineTranslationProperties: OpenaiMachineTranslationProperties,
  private val openaiApiService: OpenaiApiService,
) : AbstractMtValueProvider() {
  private val logger = LoggerFactory.getLogger(OpenaiTranslationProvider::class.java)
  
  // Store batch tasks to combine them later
  private val batchTasks = ConcurrentHashMap<String, MutableList<BatchTask>>()
  
  override val isEnabled: Boolean
    get() = !openaiMachineTranslationProperties.apiKey.isNullOrEmpty()

  override fun translateViaProvider(params: ProviderTranslateParams): MtValueProvider.MtResult {
    val batchSize = openaiMachineTranslationProperties.batchSize
    
    // Single translation or batch disabled
    if (batchSize <= 1 || !params.isBatch) {
      logger.debug("Translating text with OpenAI (single mode): '${params.text.take(50)}${if(params.text.length > 50) "..." else ""}' from ${params.sourceLanguageTag} to ${params.targetLanguageTag}")
      
      val result = openaiApiService.translate(
        params.text,
        params.sourceLanguageTag,
        params.targetLanguageTag,
      )
      return MtValueProvider.MtResult(result, params.text.length * 100)
    }
    
    // Batch translation
    val batchKey = "${params.sourceLanguageTag}_${params.targetLanguageTag}"
    
    val task = BatchTask(params.text)
    val batchTasksList = batchTasks.getOrPut(batchKey) { mutableListOf() }
    
    synchronized(batchTasksList) {
      batchTasksList.add(task)
      
      // If we've reached batch size, process the batch
      if (batchTasksList.size >= batchSize) {
        processBatch(batchKey, batchTasksList.toList())
        batchTasksList.clear()
      }
    }
    
    // Wait for this task's result
    return try {
      val result = task.waitForResult(30_000) // 30 second timeout
      MtValueProvider.MtResult(result, params.text.length * 100)
    } catch (e: Exception) {
      logger.error("Failed to get batch translation result: ${e.message}", e)
      MtValueProvider.MtResult(null, 0)
    }
  }
  
  private fun processBatch(batchKey: String, tasks: List<BatchTask>) {
    val parts = batchKey.split("_")
    if (parts.size != 2) {
      logger.error("Invalid batch key format: $batchKey")
      tasks.forEach { it.setResult(null) }
      return
    }
    
    val sourceTag = parts[0]
    val targetTag = parts[1]
    
    logger.debug("Processing batch of ${tasks.size} texts from $sourceTag to $targetTag")
    
    try {
      val texts = tasks.map { it.text }
      val results = openaiApiService.translateBatch(texts, sourceTag, targetTag)
      
      // Assign results back to individual tasks
      tasks.forEachIndexed { index, task ->
        task.setResult(results.getOrNull(index))
      }
    } catch (e: Exception) {
      logger.error("Batch translation failed: ${e.message}", e)
      // Mark all tasks as failed
      tasks.forEach { it.setError(e) }
    }
  }

  // empty array meaning all is supported
  override val supportedLanguages = arrayOf<String>()
  override val formalitySupportingLanguages = arrayOf<String>()

  override fun isLanguageSupported(tag: String): Boolean = true

  override fun isLanguageFormalitySupported(tag: String): Boolean = true
  
  private class BatchTask(val text: String) {
    private var result: String? = null
    private var error: Exception? = null
    private var completed = false
    
    @Synchronized
    fun setResult(value: String?) {
      result = value
      completed = true
      (this as Object).notifyAll()
    }
    
    @Synchronized
    fun setError(e: Exception) {
      error = e
      completed = true
      (this as Object).notifyAll()
    }
    
    @Synchronized
    fun waitForResult(timeoutMs: Long): String? {
      val endTime = System.currentTimeMillis() + timeoutMs
      while (!completed) {
        val remainingTime = endTime - System.currentTimeMillis()
        if (remainingTime <= 0) {
          throw RuntimeException("Timeout waiting for batch translation result")
        }
        (this as Object).wait(remainingTime)
      }
      
      if (error != null) {
        throw RuntimeException("Batch translation failed", error)
      }
      
      return result
    }
  }
}
