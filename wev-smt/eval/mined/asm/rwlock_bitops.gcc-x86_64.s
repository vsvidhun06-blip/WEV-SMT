	.file	"rwlock_bitops.c"
	.text
	.p2align 4
	.globl	rw_read_lock
	.type	rw_read_lock, @function
rw_read_lock:
	endbr64
.L2:
	movl	(%rdi), %eax
	testb	$1, %al
	jne	.L2
	leal	2(%rax), %edx
	lock cmpxchgl	%edx, (%rdi)
	jne	.L2
	ret
	.size	rw_read_lock, .-rw_read_lock
	.p2align 4
	.globl	rw_read_unlock
	.type	rw_read_unlock, @function
rw_read_unlock:
	endbr64
	lock subl	$2, (%rdi)
	ret
	.size	rw_read_unlock, .-rw_read_unlock
	.p2align 4
	.globl	rw_lowest_bit
	.type	rw_lowest_bit, @function
rw_lowest_bit:
	endbr64
	movl	(%rdi), %edx
	movl	%edx, %eax
	negl	%eax
	andl	%edx, %eax
	movl	%eax, (%rsi)
	ret
	.size	rw_lowest_bit, .-rw_lowest_bit
	.p2align 4
	.globl	rw_clear_lowest
	.type	rw_clear_lowest, @function
rw_clear_lowest:
	endbr64
	movl	(%rdi), %edx
	leal	-1(%rdx), %eax
	andl	%edx, %eax
	movl	%eax, (%rsi)
	ret
	.size	rw_clear_lowest, .-rw_clear_lowest
	.p2align 4
	.globl	rw_mask_degenerate
	.type	rw_mask_degenerate, @function
rw_mask_degenerate:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	rw_mask_degenerate, .-rw_mask_degenerate
	.p2align 4
	.globl	rw_or_degenerate
	.type	rw_or_degenerate, @function
rw_or_degenerate:
	endbr64
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	ret
	.size	rw_or_degenerate, .-rw_or_degenerate
	.p2align 4
	.globl	rw_roundtrip
	.type	rw_roundtrip, @function
rw_roundtrip:
	endbr64
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	ret
	.size	rw_roundtrip, .-rw_roundtrip
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
