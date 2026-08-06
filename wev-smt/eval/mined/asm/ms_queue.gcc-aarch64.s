	.arch armv8-a
	.file	"ms_queue.c"
	.text
	.global	__aarch64_cas8_rel
	.align	2
	.p2align 4,,11
	.global	ms_enqueue
	.type	ms_enqueue, %function
ms_enqueue:
.LFB0:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -16
	.cfi_offset 21, -8
	mov	x21, x1
	str	xzr, [x1]
	add	x20, x0, 8
	.p2align 3,,7
.L3:
	ldar	x19, [x20]
	ldar	x1, [x19]
	ldar	x0, [x20]
	cmp	x19, x0
	bne	.L3
	cbnz	x1, .L4
	mov	x2, x19
	mov	x1, x21
	mov	x0, 0
	bl	__aarch64_cas8_rel
	cbnz	x0, .L3
	mov	x2, x20
	mov	x1, x21
	mov	x0, x19
	bl	__aarch64_cas8_rel
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
.L4:
	.cfi_restore_state
	mov	x0, x19
	mov	x2, x20
	bl	__aarch64_cas8_rel
	b	.L3
	.cfi_endproc
.LFE0:
	.size	ms_enqueue, .-ms_enqueue
	.align	2
	.p2align 4,,11
	.global	ms_dequeue
	.type	ms_dequeue, %function
ms_dequeue:
.LFB1:
	.cfi_startproc
	stp	x30, x19, [sp, -48]!
	.cfi_def_cfa_offset 48
	.cfi_offset 30, -48
	.cfi_offset 19, -40
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -32
	.cfi_offset 21, -24
	mov	x20, x0
	mov	x21, x1
	str	x22, [sp, 32]
	.cfi_offset 22, -16
	add	x22, x0, 8
.L15:
	ldar	x19, [x20]
	ldar	x2, [x22]
	ldar	x1, [x19]
	ldar	x0, [x20]
	cmp	x19, x0
	bne	.L15
	cbz	x1, .L19
	cmp	x19, x2
	beq	.L25
	ldr	w0, [x1, 8]
	mov	x2, x20
	str	w0, [x21]
	mov	x0, x19
	bl	__aarch64_cas8_rel
	cmp	x0, x19
	bne	.L15
	mov	w0, 1
.L13:
	ldp	x20, x21, [sp, 16]
	ldr	x22, [sp, 32]
	ldp	x30, x19, [sp], 48
	.cfi_remember_state
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 22
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L25:
	.cfi_restore_state
	mov	x0, x19
	mov	x2, x22
	bl	__aarch64_cas8_rel
	b	.L15
	.p2align 2,,3
.L19:
	mov	w0, 0
	b	.L13
	.cfi_endproc
.LFE1:
	.size	ms_dequeue, .-ms_dequeue
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
