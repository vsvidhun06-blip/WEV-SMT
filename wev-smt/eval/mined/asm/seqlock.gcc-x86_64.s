	.file	"seqlock.c"
	.text
	.p2align 4
	.globl	seq_write
	.type	seq_write, @function
seq_write:
	endbr64
	movl	(%rdi), %eax
	leal	1(%rax), %ecx
	addl	$2, %eax
	movl	%ecx, (%rdi)
	movl	%esi, 4(%rdi)
	movl	%edx, 8(%rdi)
	movl	%eax, (%rdi)
	ret
	.size	seq_write, .-seq_write
	.p2align 4
	.globl	seq_read
	.type	seq_read, @function
seq_read:
	endbr64
	.p2align 4,,10
	.p2align 3
.L5:
	movl	(%rdi), %eax
	testb	$1, %al
	jne	.L4
	movl	4(%rdi), %ecx
	movl	%ecx, (%rsi)
	movl	8(%rdi), %ecx
	movl	%ecx, (%rdx)
	movl	(%rdi), %ecx
.L4:
	cmpl	%ecx, %eax
	jne	.L5
	movl	$1, %eax
	ret
	.size	seq_read, .-seq_read
	.p2align 4
	.globl	seq_read_report
	.type	seq_read_report, @function
seq_read_report:
	endbr64
	movl	(%rdi), %edx
	movl	(%rdi), %eax
	subl	%edx, %eax
	movl	%eax, (%rsi)
	ret
	.size	seq_read_report, .-seq_read_report
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
