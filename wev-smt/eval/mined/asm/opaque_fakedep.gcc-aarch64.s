	.arch armv8-a
	.file	"opaque_fakedep.c"
	.text
	.align	2
	.p2align 4,,11
	.global	ofd_volatile_xor
	.type	ofd_volatile_xor, %function
ofd_volatile_xor:
.LFB0:
	.cfi_startproc
	ldr	w2, [x0]
	ldr	w0, [x0]
	mov	w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE0:
	.size	ofd_volatile_xor, .-ofd_volatile_xor
	.align	2
	.p2align 4,,11
	.global	ofd_hidden_sub
	.type	ofd_hidden_sub, %function
ofd_hidden_sub:
.LFB1:
	.cfi_startproc
	ldr	w0, [x0]
	mov	w2, w0
	sub	w0, w0, w2
	add	w0, w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE1:
	.size	ofd_hidden_sub, .-ofd_hidden_sub
	.align	2
	.p2align 4,,11
	.global	ofd_hidden_andzero
	.type	ofd_hidden_andzero, %function
ofd_hidden_andzero:
.LFB2:
	.cfi_startproc
	ldr	w0, [x0]
	and	w0, w0, w2
	add	w0, w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE2:
	.size	ofd_hidden_andzero, .-ofd_hidden_andzero
	.align	2
	.p2align 4,,11
	.global	ofd_opaque_cancel
	.type	ofd_opaque_cancel, %function
ofd_opaque_cancel:
.LFB3:
	.cfi_startproc
	stp	x30, x19, [sp, -32]!
	.cfi_def_cfa_offset 32
	.cfi_offset 30, -32
	.cfi_offset 19, -24
	stp	x20, x21, [sp, 16]
	.cfi_offset 20, -16
	.cfi_offset 21, -8
	mov	x20, x1
	ldr	w21, [x0]
	mov	w0, w21
	bl	opaque_identity
	mov	w19, w0
	mov	w0, w21
	bl	opaque_identity
	sub	w19, w19, w0
	add	w19, w19, 1
	str	w19, [x20]
	ldp	x20, x21, [sp, 16]
	ldp	x30, x19, [sp], 32
	.cfi_restore 19
	.cfi_restore 30
	.cfi_restore 20
	.cfi_restore 21
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE3:
	.size	ofd_opaque_cancel, .-ofd_opaque_cancel
	.align	2
	.p2align 4,,11
	.global	ofd_hidden_xor
	.type	ofd_hidden_xor, %function
ofd_hidden_xor:
.LFB4:
	.cfi_startproc
	ldr	w2, [x0]
	mov	w0, w2
	eor	w0, w0, w2
	orr	w0, w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE4:
	.size	ofd_hidden_xor, .-ofd_hidden_xor
	.align	2
	.p2align 4,,11
	.global	ofd_hidden_real
	.type	ofd_hidden_real, %function
ofd_hidden_real:
.LFB5:
	.cfi_startproc
	ldr	w0, [x0]
	add	w0, w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE5:
	.size	ofd_hidden_real, .-ofd_hidden_real
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
