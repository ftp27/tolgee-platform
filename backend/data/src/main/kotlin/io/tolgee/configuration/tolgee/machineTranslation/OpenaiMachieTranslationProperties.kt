package io.tolgee.configuration.tolgee.machineTranslation

import io.tolgee.configuration.annotations.DocProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "tolgee.machine-translation.openai")
@DocProperty(
  description = "OpenAI machine translation properties",
  displayName = "OpenAI Translate",
)
open class OpenaiMachineTranslationProperties(
  @DocProperty(description = "Whether OpenAI-powered machine translation is enabled.")
  override var defaultEnabled: Boolean = true,
  @DocProperty(description = "Whether to use OpenAI Translate as a primary translation engine.")
  override var defaultPrimary: Boolean = false,
  @DocProperty(description = "OpenAI API key")
  var apiKey: String? = null,
  @DocProperty(description = "OpenAI model to use for translation")
  var model: String = "gpt-4o-mini",
  @DocProperty(description = "OpenAI API endpoint")
  var apiEndpoint: String = "https://api.openai.com/v1/chat/completions",
  @DocProperty(
    description = "Translation prompt. Should contain {source}, {target} and {text} placeholders.",
  )
  var prompt: String =
    "Translate the following text from {source} to {target}: \"{text}\". " +
      "Preserve case sensitivity, keep all emojis as they are, " + 
      "and maintain capitalization of the first letter if it was in original text." +
      "No commentaries, no extra quotation marks or punctuation marks. " + 
      "Ex.: \"hello\", Resp: hola",
  @DocProperty(description = "Connection timeout in milliseconds")
  var connectionTimeout: Int = 10000,
  @DocProperty(description = "Response timeout in milliseconds")
  var responseTimeout: Int = 30000,
  @DocProperty(description = "Maximum retry attempts for failed API calls")
  var maxRetryAttempts: Int = 3,
) : MachineTranslationServiceProperties
