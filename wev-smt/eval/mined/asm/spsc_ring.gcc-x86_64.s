	.file	"spsc_ring.c"
	.text
	.p2align 4
	.globl	spsc_push
	.type	spsc_push, @function
spsc_push:
	endbr64
	movl	(%rdi), %eax
	movl	4(%rdi), %edx
	movl	%eax, %ecx
	subl	%edx, %ecx
	xorl	%edx, %edx
	cmpl	$63, %ecx
	ja	.L1
	movl	%eax, %edx
	addl	$1, %eax
	andl	$63, %edx
	movl	%esi, 8(%rdi,%rdx,4)
	movl	$1, %edx
	movl	%eax, (%rdi)
.L1:
	movl	%edx, %eax
	ret
	.size	spsc_push, .-spsc_push
	.p2align 4
	.globl	spsc_pop
	.type	spsc_pop, @function
spsc_pop:
	endbr64
	movl	4(%rdi), %eax
	movl	(%rdi), %ecx
	xorl	%edx, %edx
	cmpl	%ecx, %eax
	je	.L5
	movl	%eax, %edx
	addl	$1, %eax
	andl	$63, %edx
	movl	8(%rdi,%rdx,4), %edx
	movl	%edx, (%rsi)
	movl	$1, %edx
	movl	%eax, 4(%rdi)
.L5:
	movl	%edx, %eax
	ret
	.size	spsc_pop, .-spsc_pop
	.p2align 4
	.globl	spsc_pop_recycle
	.type	spsc_pop_recycle, @function
spsc_pop_recycle:
	endbr64
	movl	4(%rdi), %eax
	movl	(%rdi), %ecx
	xorl	%edx, %edx
	cmpl	%ecx, %eax
	je	.L8
	movl	8(%rdi), %edx
	addl	$1, %eax
	movl	%edx, (%rsi)
	movl	$1, %edx
	movl	%eax, 4(%rdi)
.L8:
	movl	%edx, %eax
	ret
	.size	spsc_pop_recycle, .-spsc_pop_recycle
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
