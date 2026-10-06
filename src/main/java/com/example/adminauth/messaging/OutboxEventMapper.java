package com.example.adminauth.messaging;

import com.example.adminauth.event.AuditEventV1;
import com.example.adminauth.event.NotificationEventV1;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class OutboxEventMapper {

    public String toJson(SpecificRecord record) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JsonEncoder encoder = EncoderFactory.get().jsonEncoder(record.getSchema(), baos);
            SpecificDatumWriter<SpecificRecord> writer = new SpecificDatumWriter<>(record.getSchema());
            writer.write(record, encoder);
            encoder.flush();
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize Avro record to JSON: " + e.getMessage(), e);
        }
    }

    public SpecificRecord fromJson(String eventType, String topic, String json) {
        try {
            if ((topic != null && topic.contains("notification")) ||
                    (eventType != null && eventType.startsWith("ADMIN_") || "PASSWORD_RESET".equals(eventType) || "ROLES_PERMISSIONS_CHANGED".equals(eventType))) {
                SpecificDatumReader<NotificationEventV1> reader = new SpecificDatumReader<>(NotificationEventV1.getClassSchema());
                JsonDecoder decoder = DecoderFactory.get().jsonDecoder(NotificationEventV1.getClassSchema(), json);
                return reader.read(null, decoder);
            } else {
                SpecificDatumReader<AuditEventV1> reader = new SpecificDatumReader<>(AuditEventV1.getClassSchema());
                JsonDecoder decoder = DecoderFactory.get().jsonDecoder(AuditEventV1.getClassSchema(), json);
                return reader.read(null, decoder);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize JSON to Avro record for eventType=" + eventType + ": " + e.getMessage(), e);
        }
    }
}
