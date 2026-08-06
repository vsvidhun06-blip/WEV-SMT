	.arch armv8-a
	.file	"mpmc_ring.c"
	.text
	.global	__aarch64_cas4_relax
	.align	2
	.p2align 4,,11
	.global	mpmc_push
	.type	mpmc_push, %function
mpmc_push:
.LFB0:
	.cfi_startproc
	stp	x30, x19, [sp, -48]!
	.cfi_def_cfa_offset 48
	.cfi_offset 30, -48
	.cfi_offset 19, -40
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -32
	.cfi_offset 21, -24
	mov	x21, x0
	stp	x22, x23, [sp, 32]
	.cfi_offset 22, -16
	.cfi_offset 23, -8
	mov	w23, w1
.L11:
	ldr	w19, [x21]
.L2:
	and	w22, w19, 63
	add	w20, w22, 1
	ubfiz	x20, x20, 3, 7
	add	x20, x21, x20
	ldar	w3, [x20]
	subs	w3, w3, w19
	mov	x2, x21
	add	w1, w19, 1
	mov	w0, w19
	cbnz	w3, .L3
	bl	__aarch64_cas4_relax
	cmp	w0, w19
	mov	w19, w0
	bne	.L2
	ubfiz	x22, x22, 3, 6
	add	w19, w0, 1
	add	x21, x21, x22
	str	w23, [x21, 12]
	stlr	w19, [x20]
	mov	w0, 1
.L1:
	ldp	x20, x21, [sp, 16]
	ldp	x22, x23, [sp, 32]
	ldp	x30, x19, [sp], 48
	.cfi_remember_state
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 22
	.cfi_restore 23
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L3:
	.cfi_restore_state
	bpl	.L11
	mov	w0, 0
	b	.L1
	.cfi_endproc
.LFE0:
	.size	mpmc_push, .-mpmc_push
	.align	2
	.p2align 4,,11
	.global	mpmc_pop
	.type	mpmc_pop, %function
mpmc_pop:
.LFB1:
	.cfi_startproc
	stp	x30, x19, [sp, -64]!
	.cfi_def_cfa_offset 64
	.cfi_offset 30, -64
	.cfi_offset 19, -56
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -48
	.cfi_offset 21, -40
	mov	x21, x0
	stp	x22, x23, [sp, 32]
	.cfi_offset 22, -32
	.cfi_offset 23, -24
	add	x23, x0, 4
	str	x24, [sp, 48]
	.cfi_offset 24, -16
	mov	x24, x1
.L23:
	ldr	w19, [x23]
.L14:
	and	w22, w19, 63
	add	w20, w22, 1
	ubfiz	x20, x20, 3, 7
	add	x20, x21, x20
	ldar	w3, [x20]
	sub	w3, w3, #1
	mov	x2, x23
	subs	w3, w3, w19
	add	w1, w19, 1
	mov	w0, w19
	cbnz	w3, .L15
	bl	__aarch64_cas4_relax
	cmp	w0, w19
	mov	w19, w0
	bne	.L14
	ubfiz	x22, x22, 3, 6
	add	w19, w0, 64
	add	x21, x21, x22
	ldr	w0, [x21, 12]
	str	w0, [x24]
	stlr	w19, [x20]
	mov	w0, 1
.L13:
	ldp	x20, x21, [sp, 16]
	ldp	x22, x23, [sp, 32]
	ldr	x24, [sp, 48]
	ldp	x30, x19, [sp], 64
	.cfi_remember_state
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 24
	.cfi_restore 22
	.cfi_restore 23
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L15:
	.cfi_restore_state
	bpl	.L23
	mov	w0, 0
	b	.L13
	.cfi_endproc
.LFE1:
	.size	mpmc_pop, .-mpmc_pop
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
