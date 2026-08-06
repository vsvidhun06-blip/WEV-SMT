	.file	"rcu_publish.c"
	.text
	.p2align 4
	.globl	rcu_publish
	.type	rcu_publish, @function
rcu_publish:
	endbr64
	movl	%edx, (%rsi)
	movl	%ecx, 4(%rsi)
	movq	%rsi, (%rdi)
	ret
	.size	rcu_publish, .-rcu_publish
	.p2align 4
	.globl	rcu_consume
	.type	rcu_consume, @function
rcu_consume:
	endbr64
	movq	(%rdi), %rax
	testq	%rax, %rax
	je	.L5
	movl	(%rax), %eax
	ret
	.p2align 4,,10
	.p2align 3
.L5:
	movl	$-1, %eax
	ret
	.size	rcu_consume, .-rcu_consume
	.p2align 4
	.globl	rcu_consume_selfsub
	.type	rcu_consume_selfsub, @function
rcu_consume_selfsub:
	endbr64
	movq	(%rdi), %rax
	movl	(%rsi), %eax
	ret
	.size	rcu_consume_selfsub, .-rcu_consume_selfsub
	.p2align 4
	.globl	rcu_consume_andzero
	.type	rcu_consume_andzero, @function
rcu_consume_andzero:
	endbr64
	movq	(%rdi), %rax
	movl	(%rsi), %eax
	ret
	.size	rcu_consume_andzero, .-rcu_consume_andzero
	.p2align 4
	.globl	rcu_consume_selfxor
	.type	rcu_consume_selfxor, @function
rcu_consume_selfxor:
	endbr64
	movq	(%rdi), %rax
	movl	(%rsi), %eax
	ret
	.size	rcu_consume_selfxor, .-rcu_consume_selfxor
	.p2align 4
	.globl	rcu_consume_cmp
	.type	rcu_consume_cmp, @function
rcu_consume_cmp:
	endbr64
	movq	(%rdi), %rax
	cmpq	%rsi, %rax
	jne	.L11
	movl	(%rax), %eax
	ret
	.p2align 4,,10
	.p2align 3
.L11:
	movl	$-1, %eax
	ret
	.size	rcu_consume_cmp, .-rcu_consume_cmp
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
