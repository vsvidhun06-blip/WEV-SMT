	.arch armv8-a
	.file	"lkmm_fakedep.c"
	.text
	.align	2
	.global	lb_xor
	.type	lb_xor, %function
lb_xor:
.LFB0:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L2
	bl	__stack_chk_fail
.L2:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE0:
	.size	lb_xor, .-lb_xor
	.align	2
	.global	lb_linear
	.type	lb_linear, %function
lb_linear:
.LFB1:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L4
	bl	__stack_chk_fail
.L4:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE1:
	.size	lb_linear, .-lb_linear
	.align	2
	.global	lb_sub
	.type	lb_sub, %function
lb_sub:
.LFB2:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L6
	bl	__stack_chk_fail
.L6:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE2:
	.size	lb_sub, .-lb_sub
	.align	2
	.global	lb_andzero
	.type	lb_andzero, %function
lb_andzero:
.LFB3:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L8
	bl	__stack_chk_fail
.L8:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE3:
	.size	lb_andzero, .-lb_andzero
	.align	2
	.global	lb_mulzero
	.type	lb_mulzero, %function
lb_mulzero:
.LFB4:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L10
	bl	__stack_chk_fail
.L10:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE4:
	.size	lb_mulzero, .-lb_mulzero
	.align	2
	.global	lb_selfand
	.type	lb_selfand, %function
lb_selfand:
.LFB5:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	ldr	w0, [sp, 20]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L12
	bl	__stack_chk_fail
.L12:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE5:
	.size	lb_selfand, .-lb_selfand
	.align	2
	.global	lb_copy
	.type	lb_copy, %function
lb_copy:
.LFB6:
	.cfi_startproc
	sub	sp, sp, #80
	.cfi_def_cfa_offset 80
	stp	x29, x30, [sp, 64]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 64
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 56]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 40]
	ldr	x0, [sp, 40]
	ldr	w0, [x0]
	str	w0, [sp, 28]
	ldr	w0, [sp, 28]
	str	w0, [sp, 32]
	ldr	w0, [sp, 32]
	str	w0, [sp, 36]
	ldr	x0, [sp]
	str	x0, [sp, 48]
	ldr	w0, [sp, 36]
	str	w0, [sp, 28]
	ldr	w0, [sp, 28]
	mov	w1, w0
	ldr	x0, [sp, 48]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 56]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L14
	bl	__stack_chk_fail
.L14:
	ldp	x29, x30, [sp, 64]
	add	sp, sp, 80
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE6:
	.size	lb_copy, .-lb_copy
	.align	2
	.global	lb_orcompl
	.type	lb_orcompl, %function
lb_orcompl:
.LFB7:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L16
	bl	__stack_chk_fail
.L16:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE7:
	.size	lb_orcompl, .-lb_orcompl
	.align	2
	.global	lb_fakectrl
	.type	lb_fakectrl, %function
lb_fakectrl:
.LFB8:
	.cfi_startproc
	sub	sp, sp, #80
	.cfi_def_cfa_offset 80
	stp	x29, x30, [sp, 64]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 64
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 56]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 32]
	ldr	x0, [sp, 32]
	ldr	w0, [x0]
	str	w0, [sp, 24]
	ldr	w0, [sp, 24]
	str	w0, [sp, 28]
	ldr	w0, [sp, 28]
	cmp	w0, 1
	bne	.L18
	ldr	x0, [sp]
	str	x0, [sp, 48]
	mov	w0, 1
	str	w0, [sp, 24]
	ldr	w0, [sp, 24]
	mov	w1, w0
	ldr	x0, [sp, 48]
	str	w1, [x0]
	b	.L21
.L18:
	ldr	x0, [sp]
	str	x0, [sp, 40]
	mov	w0, 1
	str	w0, [sp, 24]
	ldr	w0, [sp, 24]
	mov	w1, w0
	ldr	x0, [sp, 40]
	str	w1, [x0]
.L21:
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 56]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L20
	bl	__stack_chk_fail
.L20:
	ldp	x29, x30, [sp, 64]
	add	sp, sp, 80
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE8:
	.size	lb_fakectrl, .-lb_fakectrl
	.align	2
	.global	lb_realctrl
	.type	lb_realctrl, %function
lb_realctrl:
.LFB9:
	.cfi_startproc
	sub	sp, sp, #80
	.cfi_def_cfa_offset 80
	stp	x29, x30, [sp, 64]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 64
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 56]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 32]
	ldr	x0, [sp, 32]
	ldr	w0, [x0]
	str	w0, [sp, 24]
	ldr	w0, [sp, 24]
	str	w0, [sp, 28]
	ldr	w0, [sp, 28]
	cmp	w0, 1
	bne	.L23
	ldr	x0, [sp]
	str	x0, [sp, 48]
	mov	w0, 1
	str	w0, [sp, 24]
	ldr	w0, [sp, 24]
	mov	w1, w0
	ldr	x0, [sp, 48]
	str	w1, [x0]
	b	.L26
.L23:
	ldr	x0, [sp]
	str	x0, [sp, 40]
	mov	w0, 2
	str	w0, [sp, 24]
	ldr	w0, [sp, 24]
	mov	w1, w0
	ldr	x0, [sp, 40]
	str	w1, [x0]
.L26:
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 56]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L25
	bl	__stack_chk_fail
.L25:
	ldp	x29, x30, [sp, 64]
	add	sp, sp, 80
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE9:
	.size	lb_realctrl, .-lb_realctrl
	.align	2
	.global	lb_shiftmask
	.type	lb_shiftmask, %function
lb_shiftmask:
.LFB10:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L28
	bl	__stack_chk_fail
.L28:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE10:
	.size	lb_shiftmask, .-lb_shiftmask
	.align	2
	.global	lb_modone
	.type	lb_modone, %function
lb_modone:
.LFB11:
	.cfi_startproc
	sub	sp, sp, #64
	.cfi_def_cfa_offset 64
	stp	x29, x30, [sp, 48]
	.cfi_offset 29, -16
	.cfi_offset 30, -8
	add	x29, sp, 48
	str	x0, [sp, 8]
	str	x1, [sp]
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x1, [x0]
	str	x1, [sp, 40]
	mov	x1, 0
	ldr	x0, [sp, 8]
	str	x0, [sp, 24]
	ldr	x0, [sp, 24]
	ldr	w0, [x0]
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	str	w0, [sp, 20]
	ldr	x0, [sp]
	str	x0, [sp, 32]
	mov	w0, 1
	str	w0, [sp, 16]
	ldr	w0, [sp, 16]
	mov	w1, w0
	ldr	x0, [sp, 32]
	str	w1, [x0]
	nop
	adrp	x0, :got:__stack_chk_guard
	ldr	x0, [x0, :got_lo12:__stack_chk_guard]
	ldr	x2, [sp, 40]
	ldr	x1, [x0]
	subs	x2, x2, x1
	mov	x1, 0
	beq	.L30
	bl	__stack_chk_fail
.L30:
	ldp	x29, x30, [sp, 48]
	add	sp, sp, 64
	.cfi_restore 29
	.cfi_restore 30
	.cfi_def_cfa_offset 0
	ret
	.cfi_endproc
.LFE11:
	.size	lb_modone, .-lb_modone
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
