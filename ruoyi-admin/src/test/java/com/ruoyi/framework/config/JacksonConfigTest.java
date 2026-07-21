package com.ruoyi.framework.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class JacksonConfigTest
{
    @Test
    void serializesSnowflakeIdsWithoutJavaScriptPrecisionLoss() throws Exception
    {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longIdJacksonCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        String json = mapper.writeValueAsString(new IdentifierPayload(
            2068603503726362625L, 190000000000003001L));

        assertEquals("{\"id\":\"2068603503726362625\",\"primitiveId\":\"190000000000003001\"}", json);
    }

    private static class IdentifierPayload
    {
        private final Long id;
        private final long primitiveId;

        IdentifierPayload(Long id, long primitiveId)
        {
            this.id = id;
            this.primitiveId = primitiveId;
        }

        public Long getId()
        {
            return id;
        }

        public long getPrimitiveId()
        {
            return primitiveId;
        }
    }
}


