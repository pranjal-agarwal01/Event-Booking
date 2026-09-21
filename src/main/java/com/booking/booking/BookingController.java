package com.booking.booking;

import com.booking.booking.dto.BookingResponse;
import com.booking.booking.dto.CreateBookingRequest;
import com.booking.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Hold, confirm and cancel seats")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @Operation(summary = "Hold seats for the current user (PENDING booking)")
    public ResponseEntity<BookingResponse> create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateBookingRequest request,
            @Parameter(description = "Send the same key to retry safely; a duplicate never double-books")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        BookingResponse booking = bookingService.create(principal.getId(), request, idempotencyKey);
        return ResponseEntity
                .created(URI.create("/api/v1/bookings/" + booking.reference()))
                .body(booking);
    }

    @PostMapping("/{reference}/confirm")
    @Operation(summary = "Confirm a PENDING booking; seats become BOOKED")
    public BookingResponse confirm(@AuthenticationPrincipal AppUserPrincipal principal,
                                   @PathVariable UUID reference) {
        return bookingService.confirm(principal.getId(), principal.getRole(), reference);
    }

    @PostMapping("/{reference}/cancel")
    @Operation(summary = "Cancel a booking; seats go back to AVAILABLE")
    public BookingResponse cancel(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @PathVariable UUID reference) {
        return bookingService.cancel(principal.getId(), principal.getRole(), reference);
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Get one booking (owner or admin only)")
    public BookingResponse getOne(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @PathVariable UUID reference) {
        return bookingService.getByReference(principal.getId(), principal.getRole(), reference);
    }

    @GetMapping("/me")
    @Operation(summary = "List the current user's bookings")
    public Page<BookingResponse> listMine(@AuthenticationPrincipal AppUserPrincipal principal,
                                          @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return bookingService.listMine(principal.getId(), pageable);
    }
}
