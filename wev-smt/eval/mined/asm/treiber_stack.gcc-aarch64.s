	.arch armv8-a
	.file	"treiber_stack.c"
	.text
	.global	__aarch64_cas8_rel
	.align	2
	.p2align 4,,11
	.global	treiber_push
	.type	treiber_push, %function
treiber_push:
.LFB0:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -16
	.cfi_offset 21, -8
	mov	x21, x0
	mov	x20, x1
	ldr	x19, [x0]
.L2:
	str	x19, [x20]
	mov	x0, x19
	mov	x2, x21
	mov	x1, x20
	bl	__aarch64_cas8_rel
	cmp	x0, x19
	mov	x19, x0
	bne	.L2
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
	.size	treiber_push, .-treiber_push
	.global	__aarch64_cas8_acq
	.align	2
	.p2align 4,,11
	.global	treiber_pop
	.type	treiber_pop, %function
treiber_pop:
.LFB1:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	str	x20, [sp, 16]
	.cfi_offset 20, -16
	mov	x20, x0
	ldar	x19, [x0]
.L8:
	cbz	x19, .L7
	ldr	x1, [x19]
	mov	x0, x19
	mov	x2, x20
	bl	__aarch64_cas8_acq
	cmp	x0, x19
	mov	x19, x0
	bne	.L8
.L7:
	ldr	x20, [sp, 16]
	mov	x0, x19
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE1:
	.size	treiber_pop, .-treiber_pop
	.align	2
	.p2align 4,,11
	.global	treiber_push_tagged
	.type	treiber_push_tagged, %function
treiber_push_tagged:
.LFB2:
	.cfi_startproc
	ldr	x2, [x0]
	lsl	x1, x1, 16
	orr	x1, x1, 1
	stlr	x1, [x0]
	ret
	.cfi_endproc
.LFE2:
	.size	treiber_push_tagged, .-treiber_push_tagged
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
