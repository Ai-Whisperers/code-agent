package com.eneve.agent;

import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.service.ChatService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for {@link ChatResource} that verify REST layer wiring
 * and auth enforcement.
 *
 * <p>{@link ChatService} is mocked so that no Claude calls or database access
 * is required.
 */
@QuarkusTest
class ChatResourceTest {

    @InjectMock
    ChatService chatService;

    @Test
    void unauthenticatedRequest_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"message": "hello", "conversationId": null}
                """)
        .when()
            .post("/api/chat")
        .then()
            .statusCode(401);
    }

    @Test
    @TestSecurity(user = "alice", roles = {"app_user"})
    void authenticatedRequest_callsChatService_andStreamsResponse() {
        Mockito.when(chatService.chatStream(Mockito.any(), Mockito.any(), Mockito.anyBoolean()))
               .thenReturn(Multi.createFrom().items(
                       new ChatEvent.TextDelta("Hello"),
                       new ChatEvent.Done("chat-test-id")
               ));

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"message": "Hello, can you help me?", "conversationId": null}
                """)
        .when()
            .post("/api/chat")
        .then()
            .statusCode(200)
            .contentType(containsString("text/event-stream"));
    }

    @Test
    @TestSecurity(user = "alice", roles = {"app_user"})
    void missingMessage_returnsError() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
        .when()
            .post("/api/chat")
        .then()
            .statusCode(in(java.util.List.of(400, 500)));
    }
}
