	.arch armv8-a
	.file	"seqlock.c"
	.text
	.align	2
	.p2align 4,,11
	.global	seq_write
	.type	seq_write, %function
seq_write:
.LFB0:
	.cfi_startproc
	ldr	w3, [x0]
	add	w4, w3, 1
	str	w4, [x0]
	dmb	ish
	add	x4, x0, 4
	str	w1, [x4]
	add	x1, x0, 8
	str	w2, [x1]
	dmb	ish
	add	w3, w3, 2
	str	w3, [x0]
	ret
	.cfi_endproc
.LFE0:
	.size	seq_write, .-seq_write
	.align	2
	.p2align 4,,11
	.global	seq_read
	.type	seq_read, %function
seq_read:
.LFB1:
	.cfi_startproc
	add	x6, x0, 4
	add	x5, x0, 8
	.p2align 3,,7
.L5:
	ldar	w3, [x0]
	tbnz	x3, 0, .L4
	ldr	w4, [x6]
	str	w4, [x1]
	ldr	w4, [x5]
	str	w4, [x2]
	dmb	ishld
	ldr	w4, [x0]
.L4:
	cmp	w3, w4
	bne	.L5
	mov	w0, 1
	ret
	.cfi_endproc
.LFE1:
	.size	seq_read, .-seq_read
	.align	2
	.p2align 4,,11
	.global	seq_read_report
	.type	seq_read_report, %function
seq_read_report:
.LFB2:
	.cfi_startproc
	ldar	w2, [x0]
	ldr	w0, [x0]
	sub	w0, w0, w2
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE2:
	.size	seq_read_report, .-seq_read_report
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
