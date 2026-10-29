package com.yourorg.payops.exception;

import com.yourorg.payops.model.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsMissingResourceToNotFound() {
        var response = handler.notFound(new ResourceNotFoundException("Payment", 42));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().getStatus());
        assertEquals("Payment not found: 42", response.getBody().getMessage());
    }

    @Test
    void mapsBusinessRuleToUnprocessableEntity() {
        var response = handler.rule(new BusinessRuleException("Journal is not balanced"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals(422, response.getBody().getStatus());
        assertEquals("Journal is not balanced", response.getBody().getMessage());
    }

    @Test
    void mapsValidationErrorsToBadRequestWithFieldDetails() {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "payment");
        bindingResult.addError(new FieldError("payment", "amount", "must be greater than 0"));
        var exception = new MethodArgumentNotValidException(null, bindingResult);

        var response = handler.invalid(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("amount must be greater than 0", response.getBody().getMessage());
    }

    @Test
    void mapsDatabaseConstraintErrorsToGenericConflict() {
        var response = handler.db(new DataIntegrityViolationException("private database detail"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Duplicate or constraint violation", response.getBody().getMessage());
        assertNotNull(response.getBody().getTimestamp());
    }
}
