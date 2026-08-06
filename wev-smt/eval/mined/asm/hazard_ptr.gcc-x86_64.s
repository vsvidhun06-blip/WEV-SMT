	.file	"hazard_ptr.c"
	.text
	.p2align 4
	.globl	hp_protect
	.type	hp_protect, @function
hp_protect:
	endbr64
	.p2align 4,,10
	.p2align 3
.L2:
	movq	(%rdi), %rax
	movq	%rax, %rcx
	xchgq	8(%rdi), %rcx
	movq	(%rdi), %rdx
	cmpq	%rdx, %rax
	jne	.L2
	ret
	.size	hp_protect, .-hp_protect
	.p2align 4
	.globl	hp_read
	.type	hp_read, @function
hp_read:
	endbr64
	.p2align 4,,10
	.p2align 3
.L6:
	movq	(%rdi), %rax
	movq	%rax, %rcx
	xchgq	8(%rdi), %rcx
	movq	(%rdi), %rdx
	cmpq	%rdx, %rax
	jne	.L6
	testq	%rax, %rax
	je	.L8
	movl	(%rax), %eax
	ret
.L8:
	movl	$-1, %eax
	ret
	.size	hp_read, .-hp_read
	.p2align 4
	.globl	hp_retire
	.type	hp_retire, @function
hp_retire:
	endbr64
	movq	8(%rdi), %rax
	cmpq	%rax, %rsi
	je	.L10
	movq	$0, (%rdi)
.L10:
	ret
	.size	hp_retire, .-hp_retire
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
