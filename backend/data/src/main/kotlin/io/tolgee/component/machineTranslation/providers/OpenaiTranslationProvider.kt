package io.tolgee.component.machineTranslation.providers

import io.tolgee.component.machineTranslation.MtValueProvider
import io.tolgee.configuration.tolgee.machineTranslation.OpenaiMachineTranslationProperties
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class OpenaiTranslationProvider(
  private val openaiMachineTranslationProperties: OpenaiMachineTranslationProperties,
  private val openaiApiService: OpenaiApiService,
) : AbstractMtValueProvider() {
  override val isEnabled: Boolean
    get() = !openaiMachineTranslationProperties.apiKey.isNullOrEmpty()

  override fun translateViaProvider(params: ProviderTranslateParams): MtValueProvider.MtResult {
    val result =
      openaiApiService.translate(
        params.text,
        params.sourceLanguageTag,
        params.targetLanguageTag,
      )
    return MtValueProvider.MtResult(result, params.text.length * 100)
  }

  // empty array meaning all is supported
  override val supportedLanguages = arrayOf<String>()
  override val formalitySupportingLanguages = arrayOf<String>()

  override fun isLanguageSupported(tag: String): Boolean = true

  override fun isLanguageFormalitySupported(tag: String): Boolean = true
}
