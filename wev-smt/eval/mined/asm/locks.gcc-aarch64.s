	.arch armv8-a
	.file	"locks.c"
	.text
	.align	2
	.p2align 4,,11
	.global	dekker_p0
	.type	dekker_p0, %function
dekker_p0:
.LFB0:
	.cfi_startproc
	mov	w1, 1
	str	w1, [x0]
	add	x1, x0, 4
	ldr	w1, [x1]
	cbz	w1, .L5
	mov	w0, 0
	ret
	.p2align 2,,3
.L5:
	add	x0, x0, 12
	mov	w1, 1
	str	w1, [x0]
	mov	w0, w1
	ret
	.cfi_endproc
.LFE0:
	.size	dekker_p0, .-dekker_p0
	.align	2
	.p2align 4,,11
	.global	peterson_p0
	.type	peterson_p0, %function
peterson_p0:
.LFB1:
	.cfi_startproc
	mov	w1, 1
	str	w1, [x0]
	add	x1, x0, 8
	str	wzr, [x1]
	add	x2, x0, 4
	ldar	w2, [x2]
	ldar	w1, [x1]
	cmp	w2, 0
	ccmp	w1, 0, 0, ne
	bne	.L9
	mov	w0, 0
	ret
	.p2align 2,,3
.L9:
	add	x0, x0, 12
	mov	w1, 1
	str	w1, [x0]
	mov	w0, w1
	ret
	.cfi_endproc
.LFE1:
	.size	peterson_p0, .-peterson_p0
	.global	__aarch64_ldadd4_relax
	.align	2
	.p2align 4,,11
	.global	ticket_lock
	.type	ticket_lock, %function
ticket_lock:
.LFB2:
	.cfi_startproc
	stp	x30, x19, [sp, -16]!
	.cfi_def_cfa_offset 16
	.cfi_offset 30, -16
	.cfi_offset 19, -8
	mov	x19, x0
	mov	x1, x19
	mov	w0, 1
	bl	__aarch64_ldadd4_relax
	add	x1, x19, 4
	.p2align 3,,7
.L11:
	ldar	w2, [x1]
	cmp	w2, w0
	bne	.L11
	ldp	x30, x19, [sp], 16
	.cfi_restore 19
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE2:
	.size	ticket_lock, .-ticket_lock
	.align	2
	.p2align 4,,11
	.global	ticket_unlock
	.type	ticket_unlock, %function
ticket_unlock:
.LFB3:
	.cfi_startproc
	add	x0, x0, 4
	ldr	w1, [x0]
	add	w1, w1, 1
	stlr	w1, [x0]
	ret
	.cfi_endproc
.LFE3:
	.size	ticket_unlock, .-ticket_unlock
	.align	2
	.p2align 4,,11
	.global	ticket_waiters
	.type	ticket_waiters, %function
ticket_waiters:
.LFB4:
	.cfi_startproc
	ldr	w1, [x0]
	add	x0, x0, 4
	ldr	w0, [x0]
	sub	w0, w1, w0
	ret
	.cfi_endproc
.LFE4:
	.size	ticket_waiters, .-ticket_waiters
	.global	__aarch64_swp8_acq_rel
	.align	2
	.p2align 4,,11
	.global	mcs_acquire
	.type	mcs_acquire, %function
mcs_acquire:
.LFB5:
	.cfi_startproc
	stp	x30, x19, [sp, -16]!
	.cfi_def_cfa_offset 16
	.cfi_offset 30, -16
	.cfi_offset 19, -8
	mov	x19, x1
	mov	x1, x0
	str	xzr, [x19]
	mov	x0, x19
	bl	__aarch64_swp8_acq_rel
	cbnz	x0, .L24
	ldp	x30, x19, [sp], 16
	.cfi_remember_state
	.cfi_restore 19
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L24:
	.cfi_restore_state
	add	x1, x19, 8
	mov	w2, 1
	str	w2, [x1]
	stlr	x19, [x0]
	.p2align 3,,7
.L18:
	ldar	w0, [x1]
	cbnz	w0, .L18
	ldp	x30, x19, [sp], 16
	.cfi_restore 19
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE5:
	.size	mcs_acquire, .-mcs_acquire
	.align	2
	.p2align 4,,11
	.global	mcs_release
	.type	mcs_release, %function
mcs_release:
.LFB6:
	.cfi_startproc
	ldar	x0, [x1]
	cbnz	x0, .L30
	ret
	.p2align 2,,3
.L30:
	add	x0, x0, 8
	stlr	wzr, [x0]
	ret
	.cfi_endproc
.LFE6:
	.size	mcs_release, .-mcs_release
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
