package com.ivorovi.architecture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Exports the OpenAPI contract to docs/api/openapi.json (input of the frontend api-client generator). */
class OpenApiExportIT extends IntegrationTest {

    @Test
    void exportsOpenApiDocument() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String target = System.getProperty("ivorovi.openapi.export");
        if (target == null) return;
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        JsonNode tree = mapper.readTree(body);
        ((tools.jackson.databind.node.ObjectNode) tree).remove("servers"); // host-specific, keeps the file stable
        Path path = Path.of(target);
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writeValueAsString(tree) + "\n");
    }
}
