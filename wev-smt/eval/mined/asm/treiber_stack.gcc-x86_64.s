	.file	"treiber_stack.c"
	.text
	.p2align 4
	.globl	treiber_push
	.type	treiber_push, @function
treiber_push:
	endbr64
	movq	(%rdi), %rax
.L2:
	movq	%rax, (%rsi)
	lock cmpxchgq	%rsi, (%rdi)
	jne	.L2
	ret
	.size	treiber_push, .-treiber_push
	.p2align 4
	.globl	treiber_pop
	.type	treiber_pop, @function
treiber_pop:
	endbr64
	movq	(%rdi), %rax
.L7:
	testq	%rax, %rax
	je	.L6
	movq	(%rax), %rdx
	lock cmpxchgq	%rdx, (%rdi)
	jne	.L7
.L6:
	ret
	.size	treiber_pop, .-treiber_pop
	.p2align 4
	.globl	treiber_push_tagged
	.type	treiber_push_tagged, @function
treiber_push_tagged:
	endbr64
	salq	$16, %rsi
	movq	(%rdi), %rax
	orq	$1, %rsi
	movq	%rsi, (%rdi)
	ret
	.size	treiber_push_tagged, .-treiber_push_tagged
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
	.section	.note.gnu.property,"a"
	.align 8
	.long	1f - 0f
	.long	4f - 1f
	.long	5
0:
	.string	"GNU"
1:
	.align 8
	.long	0xc0000002
	.long	3f - 2f
2:
	.long	0x3
3:
	.align 8
4:
