package dev.rdubey.wallet.infrastructure.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emits the meaningful domain events as structured JSON.
 * <p>
 * Fields are pushed through the MDC because Spring Boot's structured logging
 * lifts MDC entries into top-level JSON fields, which keeps the event payload
 * machine-readable instead of embedded in a message string. The correlation id
 * is already in the MDC for the request and is therefore attached to every
 * event automatically.
 */
@Component
public class DomainEvents
{
    private static final Logger LOG = LoggerFactory.getLogger("domain");

    public void walletCreated(UUID walletId, UUID ownerId, long balancePaise)
    {
        emit("wallet_created", Map.of("wallet_id", walletId,
                                      "user_id", ownerId,
                                      "balance_paise", balancePaise));
    }

    public void userRegistered(UUID userId)
    {
        emit("user_registered", Map.of("user_id", userId));
    }

    public void transferCreated(UUID transferId, UUID from, UUID to, long amountPaise)
    {
        emit("transfer_created", transferFields(transferId, from, to, amountPaise));
    }

    public void debited(UUID transferId, UUID walletId, long amountPaise)
    {
        emit("debited", Map.of("transfer_id", transferId,
                               "wallet_id", walletId,
                               "amount_paise", amountPaise));
    }

    public void credited(UUID transferId, UUID walletId, long amountPaise)
    {
        emit("credited", Map.of("transfer_id", transferId,
                                "wallet_id", walletId,
                                "amount_paise", amountPaise));
    }

    public void declined(UUID transferId, UUID from, UUID to, long amountPaise, String reason)
    {
        Map<String, Object> fields = transferFields(transferId, from, to, amountPaise);
        fields.put("reason", reason);
        emit("declined", fields);
    }

    public void idempotentReplay(UUID transferId, String idempotencyKey)
    {
        emit("idempotent_replay", Map.of("transfer_id", transferId,
                                         "idempotency_key", idempotencyKey));
    }

    public void idempotencyConflict(String idempotencyKey)
    {
        emit("idempotency_conflict", Map.of("idempotency_key", idempotencyKey));
    }

    private static Map<String, Object> transferFields(UUID transferId, UUID from, UUID to, long amountPaise)
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("transfer_id", transferId);
        fields.put("from_wallet_id", from);
        fields.put("to_wallet_id", to);
        fields.put("amount_paise", amountPaise);
        return fields;
    }

    private void emit(String event, Map<String, ?> fields)
    {
        List<String> pushed = new ArrayList<>(fields.size() + 1);
        try
        {
            MDC.put("event", event);
            pushed.add("event");

            for (Map.Entry<String, ?> field : fields.entrySet())
            {
                if (field.getValue() != null)
                {
                    MDC.put(field.getKey(), String.valueOf(field.getValue()));
                    pushed.add(field.getKey());
                }
            }
            LOG.info(event);
        }
        finally
        {
            pushed.forEach(MDC::remove);
        }
    }
}
