package io.tolgee.component.machineTranslation.providers

import io.tolgee.component.machineTranslation.MtValueProvider
import io.tolgee.configuration.tolgee.machineTranslation.OpenaiMachineTranslationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class OpenaiTranslationProvider(
  private val openaiMachineTranslationProperties: OpenaiMachineTranslationProperties,
  private val openaiApiService: OpenaiApiService,
) : AbstractMtValueProvider() {
  private val logger = LoggerFactory.getLogger(OpenaiTranslationProvider::class.java)
  
  override val isEnabled: Boolean
    get() = !openaiMachineTranslationProperties.apiKey.isNullOrEmpty()

  override fun translateViaProvider(params: ProviderTranslateParams): MtValueProvider.MtResult {
    logger.debug("Translating text with OpenAI: '${params.text.take(50)}${if(params.text.length > 50) "..." else ""}' from ${params.sourceLanguageTag} to ${params.targetLanguageTag}")
    
    val maxAttempts = 3
    var lastException: Exception? = null
    var currentBackoff = 1000L
    
    for (attempt in 1..maxAttempts) {
      try {
        val result = openaiApiService.translate(
          params.text,
          params.sourceLanguageTag,
          params.targetLanguageTag,
        )
        return MtValueProvider.MtResult(result, params.text.length * 100)
      } catch (e: ResourceAccessException) {
        lastException = e
        if (attempt < maxAttempts) {
          logger.warn("OpenAI API call attempt $attempt failed with ResourceAccessException: ${e.message}. Retrying in ${currentBackoff}ms...")
          Thread.sleep(currentBackoff)
          currentBackoff = (currentBackoff * 2).coerceAtMost(10000L)
        }
      } catch (e: Exception) {
        logger.error("Error translating with OpenAI: ${e.message}", e)
        throw e
      }
    }
    
    logger.error("All retry attempts for OpenAI translation failed", lastException)
    throw lastException ?: RuntimeException("All retry attempts for OpenAI translation failed")
  }

  // empty array meaning all is supported
  override val supportedLanguages = arrayOf<String>()
  override val formalitySupportingLanguages = arrayOf<String>()

  override fun isLanguageSupported(tag: String): Boolean = true

  override fun isLanguageFormalitySupported(tag: String): Boolean = true
}
