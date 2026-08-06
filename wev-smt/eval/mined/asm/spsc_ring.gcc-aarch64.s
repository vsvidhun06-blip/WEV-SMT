	.arch armv8-a
	.file	"spsc_ring.c"
	.text
	.align	2
	.p2align 4,,11
	.global	spsc_push
	.type	spsc_push, %function
spsc_push:
.LFB0:
	.cfi_startproc
	ldr	w3, [x0]
	add	x2, x0, 4
	ldar	w2, [x2]
	sub	w2, w3, w2
	cmp	w2, 63
	bls	.L5
	mov	w0, 0
	ret
	.p2align 2,,3
.L5:
	ubfiz	x2, x3, 2, 6
	add	w3, w3, 1
	add	x2, x0, x2
	str	w1, [x2, 8]
	stlr	w3, [x0]
	mov	w0, 1
	ret
	.cfi_endproc
.LFE0:
	.size	spsc_push, .-spsc_push
	.align	2
	.p2align 4,,11
	.global	spsc_pop
	.type	spsc_pop, %function
spsc_pop:
.LFB1:
	.cfi_startproc
	add	x3, x0, 4
	ldr	w2, [x3]
	ldar	w4, [x0]
	cmp	w2, w4
	beq	.L8
	ubfiz	x4, x2, 2, 6
	add	w2, w2, 1
	add	x0, x0, x4
	ldr	w0, [x0, 8]
	str	w0, [x1]
	stlr	w2, [x3]
	mov	w0, 1
	ret
	.p2align 2,,3
.L8:
	mov	w0, 0
	ret
	.cfi_endproc
.LFE1:
	.size	spsc_pop, .-spsc_pop
	.align	2
	.p2align 4,,11
	.global	spsc_pop_recycle
	.type	spsc_pop_recycle, %function
spsc_pop_recycle:
.LFB2:
	.cfi_startproc
	add	x3, x0, 4
	ldr	w2, [x3]
	ldar	w4, [x0]
	cmp	w2, w4
	beq	.L11
	ldr	w0, [x0, 8]
	add	w2, w2, 1
	str	w0, [x1]
	stlr	w2, [x3]
	mov	w0, 1
	ret
	.p2align 2,,3
.L11:
	mov	w0, 0
	ret
	.cfi_endproc
.LFE2:
	.size	spsc_pop_recycle, .-spsc_pop_recycle
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
