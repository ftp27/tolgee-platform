package io.tolgee.component.machineTranslation.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class OpenaiApiService(
  private val openaiMachineTranslationProperties: OpenaiMachineTranslationProperties,
  private val restTemplate: RestTemplate,
  private val objectMapper: ObjectMapper,
) {
  private val logger = LoggerFactory.getLogger(OpenaiApiService::class.java)
  private val rateLimiter = RateLimiter(openaiMachineTranslationProperties.requestsPerMinute)
  
  fun translate(
    text: String,
    sourceTag: String,
    targetTag: String,
  ): String? {
    // Use single-text translation
    rateLimiter.acquire()
    try {
      return translateSingle(text, sourceTag, targetTag)
    } finally {
      rateLimiter.release()
    }
  }
  
  fun translateBatch(
    texts: List<String>,
    sourceTag: String,
    targetTag: String,
  ): List<String?> {
    if (texts.isEmpty()) return emptyList()
    if (texts.size == 1) return listOf(translate(texts[0], sourceTag, targetTag))
    
    rateLimiter.acquire()
    try {
      return performBatchTranslation(texts, sourceTag, targetTag)
    } finally {
      rateLimiter.release()
    }
  }
  
  private fun translateSingle(
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
    
    return executeOpenAiRequest(requestBody, headers)?.choices?.firstOrNull()?.message?.content
  }
  
  private fun performBatchTranslation(
    texts: List<String>,
    sourceTag: String,
    targetTag: String,
  ): List<String?> {
    val headers = HttpHeaders()
    headers.contentType = MediaType.APPLICATION_JSON
    headers.add("Authorization", "Bearer ${openaiMachineTranslationProperties.apiKey}")

    // Format texts as a JSON array string for the prompt
    val textsJson = objectMapper.writeValueAsString(texts)
    
    var prompt = openaiMachineTranslationProperties.batchPrompt
    prompt = prompt.replace("{source}", sourceTag)
    prompt = prompt.replace("{target}", targetTag)
    prompt = prompt.replace("{texts}", textsJson)

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
    requestBody.addProperty("response_format", "json_object")

    logger.debug("Making batch OpenAI API call to translate ${texts.size} texts from $sourceTag to $targetTag")
    
    val responseContent = executeOpenAiRequest(requestBody, headers)?.choices?.firstOrNull()?.message?.content
    
    if (responseContent.isNullOrBlank()) {
      logger.error("Empty response from OpenAI batch translation")
      return List(texts.size) { null }
    }
    
    return try {
      // Parse JSON response
      val responseMap = objectMapper.readValue(responseContent, object : TypeReference<Map<String, Any>>() {})
      val translations = responseMap["translations"] as? List<*> ?: emptyList<String>()
      
      // Check if we received the correct number of translations
      if (translations.size != texts.size) {
        logger.warn("Received ${translations.size} translations for ${texts.size} texts")
        List(texts.size) { idx -> if (idx < translations.size) translations[idx]?.toString() else null }
      } else {
        translations.map { it?.toString() }
      }
    } catch (e: Exception) {
      // If we can't parse as a structured object, try as a simple array
      try {
        objectMapper.readValue(responseContent, object : TypeReference<List<String>>() {})
      } catch (e2: Exception) {
        logger.error("Failed to parse OpenAI batch translation response: $responseContent", e2)
        List(texts.size) { null }
      }
    }
  }
  
  private fun executeOpenAiRequest(
    requestBody: JsonObject,
    headers: HttpHeaders,
  ): OpenaiCompletionResponse? {
    val maxAttempts = openaiMachineTranslationProperties.maxRetryAttempts
    var lastException: Exception? = null
    var currentBackoff = 1000L
    
    for (attempt in 1..maxAttempts) {
      try {
        val response =
          restTemplate.postForEntity<OpenaiCompletionResponse>(
            openaiMachineTranslationProperties.apiEndpoint,
            HttpEntity(requestBody.toString(), headers),
            OpenaiCompletionResponse::class.java,
          )

        if (response.statusCode == HttpStatus.OK) {
          logger.debug("OpenAI API call successful with status: ${response.statusCodeValue}")
          return response.body
        } else {
          logger.error("OpenAI API returned non-OK status: ${response.statusCodeValue}")
          throw RuntimeException("OpenAI API returned status code: ${response.statusCodeValue}")
        }
      } catch (e: HttpClientErrorException) {
        if (e.statusCode == HttpStatus.TOO_MANY_REQUESTS) {
          lastException = e
          val backoffTime = currentBackoff * attempt
          logger.warn("Rate limit exceeded for OpenAI API. Retrying in ${backoffTime}ms...")
          Thread.sleep(backoffTime)
        } else {
          logger.error("OpenAI API client error: ${e.statusCode}, Response body: ${e.responseBodyAsString}", e)
          throw RuntimeException("OpenAI API client error: ${e.statusCode} - ${e.responseBodyAsString}", e)
        }
      } catch (e: HttpServerErrorException) {
        lastException = e
        if (attempt < maxAttempts) {
          val backoffTime = currentBackoff * attempt
          logger.warn("OpenAI API server error: ${e.statusCode}. Retrying in ${backoffTime}ms...")
          Thread.sleep(backoffTime)
        } else {
          logger.error("OpenAI API server error: ${e.statusCode}, Response body: ${e.responseBodyAsString}", e)
          throw RuntimeException("OpenAI API server error: ${e.statusCode} - ${e.responseBodyAsString}", e)
        }
      } catch (e: ResourceAccessException) {
        lastException = e
        if (attempt < maxAttempts) {
          val backoffTime = currentBackoff * attempt
          logger.warn("OpenAI API network error. Retrying in ${backoffTime}ms...")
          Thread.sleep(backoffTime)
        } else {
          logger.error("OpenAI API network error: ${e.message}", e)
          val rootCause = e.cause?.let { " Root cause: ${it.javaClass.name} - ${it.message}" } ?: ""
          throw RuntimeException("OpenAI API network error: ${e.message}.$rootCause", e)
        }
      } catch (e: Exception) {
        logger.error("Unexpected error when calling OpenAI API: ${e.javaClass.name} - ${e.message}", e)
        throw RuntimeException("Unexpected error when calling OpenAI API: ${e.javaClass.name} - ${e.message}", e)
      }
    }
    
    logger.error("All retry attempts for OpenAI API call failed", lastException)
    throw lastException ?: RuntimeException("All retry attempts for OpenAI API call failed")
  }

  // Rate limiter implementation for controlling requests per minute
  private inner class RateLimiter(requestsPerMinute: Int) {
    private val semaphore = Semaphore(requestsPerMinute)
    private val requestTimes = AtomicReference<MutableList<Instant>>(mutableListOf())
    
    init {
      // Start a cleanup thread for expired timestamps
      Thread {
        while (true) {
          try {
            cleanupExpiredTimestamps()
            TimeUnit.SECONDS.sleep(10)
          } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            break
          }
        }
      }.apply { 
        isDaemon = true
        start()
      }
    }
    
    fun acquire() {
      if (!semaphore.tryAcquire(2, TimeUnit.MINUTES)) {
        throw RuntimeException("Failed to acquire rate limit permit after waiting 2 minutes")
      }
      
      // Add current timestamp
      val now = Instant.now()
      val times = requestTimes.get()
      times.add(now)
      requestTimes.set(times)
    }
    
    fun release() {
      cleanupExpiredTimestamps()
      semaphore.release()
    }
    
    private fun cleanupExpiredTimestamps() {
      val now = Instant.now()
      val times = requestTimes.get()
      val oneMinuteAgo = now.minus(Duration.ofMinutes(1))
      
      val validTimes = times.filter { it.isAfter(oneMinuteAgo) }.toMutableList()
      if (validTimes.size != times.size) {
        requestTimes.set(validTimes)
      }
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
