package uk.gov.moj.cp.ai.service;

import uk.gov.moj.cp.ai.exception.ChatServiceException;

import java.util.Optional;

public interface ChatService {

    <T> Optional<T> callModel(String systemInstruction, String userInstruction, Class<T> responseClass) throws ChatServiceException;
}
