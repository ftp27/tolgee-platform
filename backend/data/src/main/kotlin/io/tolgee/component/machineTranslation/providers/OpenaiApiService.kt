package io.tolgee.component.machineTranslation.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.tolgee.configuration.tolgee.machineTranslation.OpenaiMachineTranslationProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class OpenaiApiService(
  private val openaiMachineTranslationProperties: OpenaiMachineTranslationProperties,
  private val restTemplate: RestTemplate,
) {
  private val logger = LoggerFactory.getLogger(OpenaiApiService::class.java)

  fun translate(
    text: String,
    sourceTag: String,
    targetTag: String,
  ): String? {
    val headers = HttpHeaders()
    headers.contentType = MediaType.APPLICATION_JSON
    headers.add("Authorization", "Bearer ${openaiMachineTranslationProperties.apiKey}")

    var prompt = openaiMachineTranslationProperties.prompt
    prompt = prompt.replace("{source}", sourceTag)
    prompt = prompt.replace("{target}", targetTag)
    prompt = prompt.replace("{text}", text)

    val requestBody = JsonObject()
    requestBody.addProperty("model", openaiMachineTranslationProperties.model)
    requestBody.add(
      "messages",
      JsonArray().apply {
        add(
          JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", prompt)
          },
        )
      },
    )

    logger.debug("Making OpenAI API call to translate text of length ${text.length} from $sourceTag to $targetTag")
    
    try {
      val response =
        restTemplate.postForEntity<OpenaiCompletionResponse>(
          openaiMachineTranslationProperties.apiEndpoint,
          HttpEntity(requestBody.toString(), headers),
          OpenaiCompletionResponse::class.java,
        )

      if (response.statusCode == HttpStatus.OK) {
        logger.debug("OpenAI API call successful with status: ${response.statusCodeValue}")
        return response.body?.choices?.firstOrNull()?.message?.content
          ?: throw RuntimeException("Empty response from OpenAI API: $response")
      } else {
        logger.error("OpenAI API returned non-OK status: ${response.statusCodeValue}")
        throw RuntimeException("OpenAI API returned status code: ${response.statusCodeValue}")
      }
    } catch (e: HttpClientErrorException) {
      logger.error("OpenAI API client error: ${e.statusCode}, Response body: ${e.responseBodyAsString}", e)
      throw RuntimeException("OpenAI API client error: ${e.statusCode} - ${e.responseBodyAsString}", e)
    } catch (e: HttpServerErrorException) {
      logger.error("OpenAI API server error: ${e.statusCode}, Response body: ${e.responseBodyAsString}", e)
      throw RuntimeException("OpenAI API server error: ${e.statusCode} - ${e.responseBodyAsString}", e)
    } catch (e: ResourceAccessException) {
      logger.error("OpenAI API network error: ${e.message}", e)
      val rootCause = e.cause?.let { " Root cause: ${it.javaClass.name} - ${it.message}" } ?: ""
      throw RuntimeException("OpenAI API network error: ${e.message}.$rootCause", e)
    } catch (e: Exception) {
      logger.error("Unexpected error when calling OpenAI API: ${e.javaClass.name} - ${e.message}", e)
      throw RuntimeException("Unexpected error when calling OpenAI API: ${e.javaClass.name} - ${e.message}", e)
    }
  }

  companion object {
    class OpenaiCompletionResponse {
      @JsonProperty("choices")
      var choices: List<OpenaiChoice>? = null
    }

    class OpenaiChoice {
      @JsonProperty("message")
      var message: OpenaiMessage? = null
    }

    class OpenaiMessage {
      @JsonProperty("content")
      var content: String? = null
    }
  }
}
