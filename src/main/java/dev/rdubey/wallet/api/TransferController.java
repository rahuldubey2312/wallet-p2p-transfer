package dev.rdubey.wallet.api;

import dev.rdubey.wallet.api.dto.TransferRequest;
import dev.rdubey.wallet.api.dto.TransferResponse;
import dev.rdubey.wallet.api.filter.BearerAuthFilter;
import dev.rdubey.wallet.application.TransferCommand;
import dev.rdubey.wallet.application.TransferOutcome;
import dev.rdubey.wallet.application.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TransferController
{
    /**
     * Marks a response that was served from an earlier request with the same
     * idempotency key. It is a header rather than a body field so that the
     * body of a replay stays byte-identical to the original.
     */
    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private final TransferService transferService;

    public TransferController(TransferService transferService)
    {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @RequestAttribute(BearerAuthFilter.USER_ID_ATTRIBUTE) UUID userId,
            @Valid @RequestBody TransferRequest request)
    {
        TransferOutcome outcome = transferService.transfer(new TransferCommand(userId,
                                                                              request.from(),
                                                                              request.to(),
                                                                              request.amountPaise(),
                                                                              request.idempotencyKey()));

        HttpStatus status = outcome.transfer().isCompleted()
                ? HttpStatus.CREATED
                : HttpStatus.UNPROCESSABLE_CONTENT;

        return ResponseEntity.status(status)
                             .header(REPLAY_HEADER, Boolean.toString(outcome.idempotentReplay()))
                             .body(TransferResponse.from(outcome.transfer()));
    }

    @GetMapping("/transfers/{id}")
    public TransferResponse get(@PathVariable("id") UUID id)
    {
        return TransferResponse.from(transferService.getById(id));
    }
}
