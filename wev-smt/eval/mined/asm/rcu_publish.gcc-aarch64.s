	.arch armv8-a
	.file	"rcu_publish.c"
	.text
	.align	2
	.p2align 4,,11
	.global	rcu_publish
	.type	rcu_publish, %function
rcu_publish:
.LFB0:
	.cfi_startproc
	stp	w2, w3, [x1]
	stlr	x1, [x0]
	ret
	.cfi_endproc
.LFE0:
	.size	rcu_publish, .-rcu_publish
	.align	2
	.p2align 4,,11
	.global	rcu_consume
	.type	rcu_consume, %function
rcu_consume:
.LFB1:
	.cfi_startproc
	ldar	x0, [x0]
	cbz	x0, .L5
	ldr	w0, [x0]
	ret
	.p2align 2,,3
.L5:
	mov	w0, -1
	ret
	.cfi_endproc
.LFE1:
	.size	rcu_consume, .-rcu_consume
	.align	2
	.p2align 4,,11
	.global	rcu_consume_selfsub
	.type	rcu_consume_selfsub, %function
rcu_consume_selfsub:
.LFB2:
	.cfi_startproc
	ldar	x0, [x0]
	ldr	w0, [x1]
	ret
	.cfi_endproc
.LFE2:
	.size	rcu_consume_selfsub, .-rcu_consume_selfsub
	.align	2
	.p2align 4,,11
	.global	rcu_consume_andzero
	.type	rcu_consume_andzero, %function
rcu_consume_andzero:
.LFB7:
	.cfi_startproc
	ldar	x0, [x0]
	ldr	w0, [x1]
	ret
	.cfi_endproc
.LFE7:
	.size	rcu_consume_andzero, .-rcu_consume_andzero
	.align	2
	.p2align 4,,11
	.global	rcu_consume_selfxor
	.type	rcu_consume_selfxor, %function
rcu_consume_selfxor:
.LFB9:
	.cfi_startproc
	ldar	x0, [x0]
	ldr	w0, [x1]
	ret
	.cfi_endproc
.LFE9:
	.size	rcu_consume_selfxor, .-rcu_consume_selfxor
	.align	2
	.p2align 4,,11
	.global	rcu_consume_cmp
	.type	rcu_consume_cmp, %function
rcu_consume_cmp:
.LFB5:
	.cfi_startproc
	ldar	x0, [x0]
	cmp	x0, x1
	bne	.L11
	ldr	w0, [x0]
	ret
	.p2align 2,,3
.L11:
	mov	w0, -1
	ret
	.cfi_endproc
.LFE5:
	.size	rcu_consume_cmp, .-rcu_consume_cmp
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
