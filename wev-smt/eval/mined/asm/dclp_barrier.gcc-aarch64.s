	.arch armv8-a
	.file	"dclp_barrier.c"
	.text
	.global	__aarch64_swp4_acq
	.align	2
	.p2align 4,,11
	.global	dclp_get
	.type	dclp_get, %function
dclp_get:
.LFB0:
	.cfi_startproc
	ldar	x2, [x0]
	cbnz	x2, .L8
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	add	x19, x0, 8
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -16
	.cfi_offset 21, -8
	mov	x20, x0
	mov	x21, x1
	.p2align 3,,7
.L2:
	mov	x1, x19
	mov	w0, 1
	bl	__aarch64_swp4_acq
	cbnz	w0, .L2
	ldr	x2, [x20]
	mov	x0, x2
	cbz	x2, .L14
	stlr	wzr, [x19]
	ldp	x20, x21, [sp, 16]
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L8:
	mov	x0, x2
	ret
	.p2align 2,,3
.L14:
	.cfi_def_cfa_offset 32
	.cfi_offset 19, -24
	.cfi_offset 20, -16
	.cfi_offset 21, -8
	.cfi_offset 30, -32
	stlr	x21, [x20]
	mov	x0, x21
	stlr	wzr, [x19]
	ldp	x20, x21, [sp, 16]
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE0:
	.size	dclp_get, .-dclp_get
	.global	__aarch64_ldadd4_acq_rel
	.align	2
	.p2align 4,,11
	.global	barrier_wait
	.type	barrier_wait, %function
barrier_wait:
.LFB1:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	mov	x19, x2
	ldr	w2, [x2]
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -16
	.cfi_offset 21, -8
	mov	x20, x0
	mov	w0, 1
	eor	w2, w2, w0
	str	w2, [x19]
	mov	w21, w1
	mov	x1, x20
	bl	__aarch64_ldadd4_acq_rel
	add	w0, w0, 1
	add	x2, x20, 4
	cmp	w21, w0
	beq	.L22
	.p2align 3,,7
.L16:
	ldar	w1, [x2]
	ldr	w0, [x19]
	cmp	w1, w0
	bne	.L16
	ldp	x20, x21, [sp, 16]
	ldp	x30, x19, [sp], 32
	.cfi_remember_state
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L22:
	.cfi_restore_state
	str	wzr, [x20]
	ldr	w0, [x19]
	stlr	w0, [x2]
	ldp	x20, x21, [sp, 16]
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE1:
	.size	barrier_wait, .-barrier_wait
	.global	__aarch64_ldadd4_relax
	.align	2
	.p2align 4,,11
	.global	rc_acquire
	.type	rc_acquire, %function
rc_acquire:
.LFB2:
	.cfi_startproc
	str	x30, [sp, -16]!
	.cfi_def_cfa_offset 16
	.cfi_offset 30, -16
	mov	x1, x0
	mov	w0, 1
	bl	__aarch64_ldadd4_relax
	ldr	x30, [sp], 16
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE2:
	.size	rc_acquire, .-rc_acquire
	.global	__aarch64_ldadd4_rel
	.align	2
	.p2align 4,,11
	.global	rc_release
	.type	rc_release, %function
rc_release:
.LFB3:
	.cfi_startproc
	str	x30, [sp, -16]!
	.cfi_def_cfa_offset 16
	.cfi_offset 30, -16
	mov	x1, x0
	mov	w0, -1
	bl	__aarch64_ldadd4_rel
	cmp	w0, 1
	beq	.L29
	ldr	x30, [sp], 16
	.cfi_remember_state
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	mov	w0, 0
	ret
	.p2align 2,,3
.L29:
	.cfi_restore_state
	dmb	ishld
	ldr	x30, [sp], 16
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE3:
	.size	rc_release, .-rc_release
	.align	2
	.p2align 4,,11
	.global	rc_rebalance
	.type	rc_rebalance, %function
rc_rebalance:
.LFB4:
	.cfi_startproc
	ldar	w1, [x1]
	mov	w1, 1
	add	x0, x0, 4
	str	w1, [x0]
	ret
	.cfi_endproc
.LFE4:
	.size	rc_rebalance, .-rc_rebalance
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
