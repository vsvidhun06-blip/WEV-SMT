	.arch armv8-a
	.file	"chase_lev.c"
	.text
	.align	2
	.p2align 4,,11
	.global	cl_push
	.type	cl_push, %function
cl_push:
.LFB0:
	.cfi_startproc
	add	x4, x0, 8
	ldr	x3, [x4]
	ldar	x2, [x0]
	sub	x2, x3, x2
	cmp	x2, 1023
	ble	.L4
	ret
	.p2align 2,,3
.L4:
	ubfiz	x2, x3, 2, 10
	add	x2, x2, 16
	add	x0, x0, x2
	str	w1, [x0]
	dmb	ish
	add	x3, x3, 1
	str	x3, [x4]
	ret
	.cfi_endproc
.LFE0:
	.size	cl_push, .-cl_push
	.global	__aarch64_cas8_acq_rel
	.align	2
	.p2align 4,,11
	.global	cl_steal
	.type	cl_steal, %function
cl_steal:
.LFB1:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	mov	x2, x0
	ldar	x19, [x0]
	dmb	ish
	add	x0, x0, 8
	ldar	x0, [x0]
	cmp	x19, x0
	blt	.L6
.L8:
	mov	w0, 0
	ldp	x30, x19, [sp], 32
	.cfi_remember_state
	.cfi_restore 19
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L6:
	.cfi_restore_state
	ubfiz	x0, x19, 2, 10
	stp	x20, x21, [sp, 16]
	.cfi_offset 21, -8
	.cfi_offset 20, -16
	add	x0, x0, 16
	mov	x20, x1
	add	x0, x2, x0
	ldr	w21, [x0]
	add	x1, x19, 1
	mov	x0, x19
	bl	__aarch64_cas8_acq_rel
	cmp	x0, x19
	bne	.L13
	str	w21, [x20]
	mov	w0, 1
	ldp	x20, x21, [sp, 16]
	.cfi_remember_state
	.cfi_restore 21
	.cfi_restore 20
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
.L13:
	.cfi_restore_state
	ldp	x20, x21, [sp, 16]
	.cfi_restore 21
	.cfi_restore 20
	b	.L8
	.cfi_endproc
.LFE1:
	.size	cl_steal, .-cl_steal
	.align	2
	.p2align 4,,11
	.global	cl_take
	.type	cl_take, %function
cl_take:
.LFB2:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	add	x19, x0, 8
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -16
	.cfi_offset 21, -8
	ldr	x20, [x19]
	sub	x3, x20, #1
	str	x3, [x19]
	dmb	ish
	ldr	x21, [x0]
	cmp	x21, x3
	bgt	.L22
	ubfiz	x3, x3, 2, 10
	add	x3, x3, 16
	add	x3, x0, x3
	ldr	w2, [x3]
	str	w2, [x1]
	beq	.L17
.L20:
	ldp	x20, x21, [sp, 16]
	mov	w0, 1
	ldp	x30, x19, [sp], 32
	.cfi_remember_state
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.p2align 2,,3
.L17:
	.cfi_restore_state
	mov	x2, x0
	mov	x1, x20
	mov	x0, x21
	bl	__aarch64_cas8_acq_rel
	cmp	x0, x21
	bne	.L22
	str	x20, [x19]
	b	.L20
	.p2align 2,,3
.L22:
	str	x20, [x19]
	ldp	x20, x21, [sp, 16]
	mov	w0, 0
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE2:
	.size	cl_take, .-cl_take
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
