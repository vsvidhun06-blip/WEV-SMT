	.arch armv8-a
	.file	"hazard_ptr.c"
	.text
	.align	2
	.p2align 4,,11
	.global	hp_protect
	.type	hp_protect, %function
hp_protect:
.LFB0:
	.cfi_startproc
	mov	x1, x0
	add	x3, x0, 8
	.p2align 3,,7
.L2:
	ldar	x0, [x1]
	stlr	x0, [x3]
	ldar	x2, [x1]
	cmp	x0, x2
	bne	.L2
	ret
	.cfi_endproc
.LFE0:
	.size	hp_protect, .-hp_protect
	.align	2
	.p2align 4,,11
	.global	hp_read
	.type	hp_read, %function
hp_read:
.LFB1:
	.cfi_startproc
	add	x3, x0, 8
	.p2align 3,,7
.L6:
	ldar	x1, [x0]
	stlr	x1, [x3]
	ldar	x2, [x0]
	cmp	x1, x2
	bne	.L6
	cbz	x1, .L8
	ldr	w0, [x1]
	ret
.L8:
	mov	w0, -1
	ret
	.cfi_endproc
.LFE1:
	.size	hp_read, .-hp_read
	.align	2
	.p2align 4,,11
	.global	hp_retire
	.type	hp_retire, %function
hp_retire:
.LFB2:
	.cfi_startproc
	add	x2, x0, 8
	ldar	x2, [x2]
	cmp	x1, x2
	beq	.L10
	stlr	xzr, [x0]
.L10:
	ret
	.cfi_endproc
.LFE2:
	.size	hp_retire, .-hp_retire
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
