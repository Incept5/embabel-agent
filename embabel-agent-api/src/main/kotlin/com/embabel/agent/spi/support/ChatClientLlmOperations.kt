/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.spi.support

import com.embabel.agent.common.RetryTemplateProvider
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.support.AbstractLlmOperations
import com.embabel.agent.event.LlmRequestEvent
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmCall
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.ToolDecorator
import com.embabel.common.ai.model.*
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.DatabindException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ResponseEntity
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.ParameterizedTypeReference
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Service
import java.lang.reflect.ParameterizedType
import java.time.Duration
import java.time.Instant

/**
 * We want to be more forgiving with data binding. This
 * can be important for smaller models.
 */
@ConfigurationProperties(prefix = "embabel.llm-operations.data-binding")
data class LlmDataBindingProperties(
    override val maxAttempts: Int = 10,
    val fixedBackoffMillis: Long = 30L,
) : RetryTemplateProvider {
    private val logger = LoggerFactory.getLogger(LlmDataBindingProperties::class.java)

    override fun retryTemplate(): RetryTemplate {
        return RetryTemplate.builder()
            .maxAttempts(maxAttempts)
            .fixedBackoff(Duration.ofMillis(fixedBackoffMillis))
            .withListener(object : RetryListener {
                override fun <T : Any, E : Throwable> onError(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable
                ) {
                    logger.info(
                        "Retry attempt {} of {} due to: {}",
                        context.retryCount,
                        maxAttempts,
                        throwable.message ?: "Unknown error"
                    )
                }
            })
            .build()
    }
}

/**
 * Properties for the ChatClientLlmOperations operations
 * @param maybePromptTemplate template to use for the "maybe" prompt, which
 *  * can enable a failure result if the LLM does not have enough information to
 *  * create the desired output structure.
 */
@ConfigurationProperties(prefix = "embabel.llm-operations.prompts")
data class LlmOperationsPromptsProperties(
    val maybePromptTemplate: String = "maybe_prompt_contribution",
    val generateExamplesByDefault: Boolean = true,
)

/**
 * LlmOperations implementation that uses the Spring AI ChatClient
 * @param modelProvider ModelProvider to get the LLM model
 * @param toolDecorator ToolDecorator to decorate tools to make them aware of platform
 * @param templateRenderer TemplateRenderer to render templates
 * @param dataBindingProperties properties
 */
@Service
internal class ChatClientLlmOperations(
    private val modelProvider: ModelProvider,
    toolDecorator: ToolDecorator,
    private val templateRenderer: TemplateRenderer,
    private val autoLlmSelectionCriteriaResolver: AutoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
    private val dataBindingProperties: LlmDataBindingProperties = LlmDataBindingProperties(),
    private val llmOperationsPromptsProperties: LlmOperationsPromptsProperties = LlmOperationsPromptsProperties(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : AbstractLlmOperations(toolDecorator) {

    @Suppress("UNCHECKED_CAST")
    override fun <O> doTransform(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>?,
    ): O {

        val resources = getLlmInvocationResources(interaction.llm)
        val promptContributions =
            (interaction.promptContributors + resources.llm.promptContributors).joinToString("\n") { it.contribution() }

        val springAiPrompt = Prompt(
            buildList {
                if (promptContributions.isNotEmpty()) {
                    add(SystemMessage(promptContributions))
                }
                add(UserMessage(prompt))
            }
        )
        llmRequestEvent?.let {
            it.agentProcess.processContext.onProcessEvent(
                it.callEvent(springAiPrompt)
            )
        }

        return dataBindingProperties.retryTemplate().execute<O, DatabindException> {
            val callResponse = resources.chatClient
                .prompt(springAiPrompt)
                // Try to lock to correct overload. Method overloading is evil.
                .toolCallbacks(interaction.toolCallbacks)
                .call()
            if (outputClass == String::class.java) {
                val chatResponse = callResponse.chatResponse()
                chatResponse?.let { recordUsage(resources.llm, it, llmRequestEvent) }
                chatResponse!!.result.output.text as O
            } else {
                val re = callResponse.responseEntity<O>(
                    WithExampleConverter(
                        delegate = SuppressThinkingConverter(BeanOutputConverter(outputClass, objectMapper)),
                        outputClass = outputClass,
                        ifPossible = false,
                        generateExamples = shouldGenerateExamples(interaction),
                    ),
                )
                re.response?.let { recordUsage(resources.llm, it, llmRequestEvent) }
                re.entity!!
            }
        }
    }

    private fun recordUsage(
        llm: Llm,
        chatResponse: ChatResponse,
        llmRequestEvent: LlmRequestEvent<*>?,
    ) {
        logger.debug("Usage is {}", chatResponse.metadata.usage)
        llmRequestEvent?.let {
            val llmi = LlmInvocation(
                llm = llm,
                usage = chatResponse.metadata.usage,
                agentName = it.agentProcess.agent.name,
                timestamp = it.timestamp,
                runningTime = Duration.between(it.timestamp, Instant.now()),
            )
            it.agentProcess.recordLlmInvocation(llmi)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <O> doTransformIfPossible(
        prompt: String,
        interaction: LlmInteraction,
        outputClass: Class<O>,
        llmRequestEvent: LlmRequestEvent<O>,
    ): Result<O> {
        val maybeReturnPromptContribution = templateRenderer.renderLoadedTemplate(
            llmOperationsPromptsProperties.maybePromptTemplate,
            emptyMap(),
        )

        val resources = getLlmInvocationResources(interaction.llm)
        val promptContributions =
            (interaction.promptContributors + resources.llm.promptContributors).joinToString("\n") { it.contribution() }
        val springAiPrompt = Prompt(
            buildList {
                if (promptContributions.isNotEmpty()) {
                    add(SystemMessage(promptContributions))
                }
                add(UserMessage("Instruction: <$prompt>\n\n$maybeReturnPromptContribution"))
            }
        )
        llmRequestEvent.agentProcess.processContext.onProcessEvent(
            llmRequestEvent.callEvent(springAiPrompt)
        )

        val typeReference = createParameterizedTypeReference<MaybeReturn<*>>(
            MaybeReturn::class.java,
            outputClass,
        )
        return dataBindingProperties.retryTemplate().execute<Result<O>, DatabindException> {
            val responseEntity: ResponseEntity<ChatResponse, MaybeReturn<*>> = resources.chatClient
                .prompt(springAiPrompt)
                .toolCallbacks(interaction.toolCallbacks)
                .call()
                .responseEntity<MaybeReturn<*>>(
                    WithExampleConverter(
                        // TODO why does passing ObjectMapper break unit test?
                        delegate = SuppressThinkingConverter(BeanOutputConverter(typeReference /*objectMapper*/)),
                        outputClass = outputClass as Class<MaybeReturn<*>>,
                        ifPossible = true,
                        generateExamples = shouldGenerateExamples(interaction),
                    )
                )
            responseEntity.response?.let { recordUsage(resources.llm, it, llmRequestEvent) }
            responseEntity.entity!!.toResult() as Result<O>
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createParameterizedTypeReference(
        rawType: Class<*>,
        typeArgument: Class<*>
    ): ParameterizedTypeReference<T> {
        // Create a type with proper generic information
        val type = object : ParameterizedType {
            override fun getRawType() = rawType
            override fun getActualTypeArguments() = arrayOf(typeArgument)
            override fun getOwnerType() = null
        }

        // Create a ParameterizedTypeReference that uses our custom type
        return object : ParameterizedTypeReference<T>() {
            override fun getType() = type
        }
    }

    /**
     * LLM we're calling and Spring AI ChatClient we'll use
     */
    private data class LlmInvocationResources(
        val llm: Llm,
        val chatClient: ChatClient,
    )

    private fun getLlmInvocationResources(
        llmOptions: LlmOptions,
    ): LlmInvocationResources {
        val crit: ModelSelectionCriteria = when (llmOptions.criteria) {
            null -> DefaultModelSelectionCriteria
            is AutoModelSelectionCriteria ->
                autoLlmSelectionCriteriaResolver.resolveAutoLlm()

            else -> llmOptions.criteria ?: DefaultModelSelectionCriteria
        }
        val llm = modelProvider.getLlm(crit)
        val chatClient = ChatClient
            .builder(llm.model)
            .defaultOptions(
                // TODO this should not be OpenAI specific but we lose tools if we aren't
//                llm.optionsConverter.invoke(llmOptions)
                OpenAiChatOptions.builder()
                    .temperature(llmOptions.temperature)
                    .build()
            )
            .build()
        return LlmInvocationResources(chatClient = chatClient, llm = llm)
    }

    private fun shouldGenerateExamples(llmCall: LlmCall): Boolean {
        if (llmOperationsPromptsProperties.generateExamplesByDefault) {
            return llmCall.generateExamples != false
        }
        return llmCall.generateExamples == true
    }
}

/**
 * Structure to be returned by the LLM.
 * Allows the LLM to return a result structure, under success, or an error message
 * One of success or failure must be set, but not both.
 */
internal data class MaybeReturn<T>(
    val success: T? = null,
    val failure: String? = null,
) {

    fun toResult(): Result<T> {
        return if (success != null) {
            Result.success(success)
        } else {
            Result.failure(Exception(failure))
        }
    }
}
