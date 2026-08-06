	.arch armv8-a
	.file	"rwlock_bitops.c"
	.text
	.global	__aarch64_cas4_acq
	.align	2
	.p2align 4,,11
	.global	rw_read_lock
	.type	rw_read_lock, %function
rw_read_lock:
.LFB0:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	str	x20, [sp, 16]
	.cfi_offset 20, -16
	mov	x20, x0
.L2:
	ldar	w19, [x20]
	tbnz	x19, 0, .L2
	mov	x2, x20
	add	w1, w19, 2
	mov	w0, w19
	bl	__aarch64_cas4_acq
	cmp	w0, w19
	bne	.L2
	ldr	x20, [sp, 16]
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE0:
	.size	rw_read_lock, .-rw_read_lock
	.global	__aarch64_ldadd4_rel
	.align	2
	.p2align 4,,11
	.global	rw_read_unlock
	.type	rw_read_unlock, %function
rw_read_unlock:
.LFB1:
	.cfi_startproc
	str	x30, [sp, -16]!
	.cfi_def_cfa_offset 16
	.cfi_offset 30, -16
	mov	x1, x0
	mov	w0, -2
	bl	__aarch64_ldadd4_rel
	ldr	x30, [sp], 16
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE1:
	.size	rw_read_unlock, .-rw_read_unlock
	.align	2
	.p2align 4,,11
	.global	rw_lowest_bit
	.type	rw_lowest_bit, %function
rw_lowest_bit:
.LFB2:
	.cfi_startproc
	ldar	w2, [x0]
	neg	w0, w2
	and	w0, w0, w2
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE2:
	.size	rw_lowest_bit, .-rw_lowest_bit
	.align	2
	.p2align 4,,11
	.global	rw_clear_lowest
	.type	rw_clear_lowest, %function
rw_clear_lowest:
.LFB3:
	.cfi_startproc
	ldar	w2, [x0]
	sub	w0, w2, #1
	and	w0, w0, w2
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE3:
	.size	rw_clear_lowest, .-rw_clear_lowest
	.align	2
	.p2align 4,,11
	.global	rw_mask_degenerate
	.type	rw_mask_degenerate, %function
rw_mask_degenerate:
.LFB4:
	.cfi_startproc
	ldar	w0, [x0]
	mov	w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE4:
	.size	rw_mask_degenerate, .-rw_mask_degenerate
	.align	2
	.p2align 4,,11
	.global	rw_or_degenerate
	.type	rw_or_degenerate, %function
rw_or_degenerate:
.LFB8:
	.cfi_startproc
	ldar	w0, [x0]
	mov	w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE8:
	.size	rw_or_degenerate, .-rw_or_degenerate
	.align	2
	.p2align 4,,11
	.global	rw_roundtrip
	.type	rw_roundtrip, %function
rw_roundtrip:
.LFB6:
	.cfi_startproc
	ldar	w0, [x0]
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE6:
	.size	rw_roundtrip, .-rw_roundtrip
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
