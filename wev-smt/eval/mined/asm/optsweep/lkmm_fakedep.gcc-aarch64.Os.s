	.arch armv8-a
	.file	"lkmm_fakedep.c"
	.text
	.align	2
	.global	lb_xor
	.type	lb_xor, %function
lb_xor:
.LFB0:
	.cfi_startproc
	ldr	w0, [x0]
	mov	w0, 1
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE0:
	.size	lb_xor, .-lb_xor
	.align	2
	.global	lb_linear
	.type	lb_linear, %function
lb_linear:
.LFB13:
	.cfi_startproc
	b	lb_xor
	.cfi_endproc
.LFE13:
	.size	lb_linear, .-lb_linear
	.align	2
	.global	lb_sub
	.type	lb_sub, %function
lb_sub:
.LFB15:
	.cfi_startproc
	b	lb_xor
	.cfi_endproc
.LFE15:
	.size	lb_sub, .-lb_sub
	.align	2
	.global	lb_andzero
	.type	lb_andzero, %function
lb_andzero:
.LFB17:
	.cfi_startproc
	b	lb_xor
	.cfi_endproc
.LFE17:
	.size	lb_andzero, .-lb_andzero
	.align	2
	.global	lb_mulzero
	.type	lb_mulzero, %function
lb_mulzero:
.LFB19:
	.cfi_startproc
	b	lb_xor
	.cfi_endproc
.LFE19:
	.size	lb_mulzero, .-lb_mulzero
	.align	2
	.global	lb_selfand
	.type	lb_selfand, %function
lb_selfand:
.LFB5:
	.cfi_startproc
	ldr	w0, [x0]
	str	w0, [x1]
	ret
	.cfi_endproc
.LFE5:
	.size	lb_selfand, .-lb_selfand
	.align	2
	.global	lb_copy
	.type	lb_copy, %function
lb_copy:
.LFB27:
	.cfi_startproc
	b	lb_selfand
	.cfi_endproc
.LFE27:
	.size	lb_copy, .-lb_copy
	.align	2
	.global	lb_orcompl
	.type	lb_orcompl, %function
lb_orcompl:
.LFB21:
	.cfi_startproc
	b	lb_xor
	.cfi_endproc
.LFE21:
	.size	lb_orcompl, .-lb_orcompl
	.align	2
	.global	lb_fakectrl
	.type	lb_fakectrl, %function
lb_fakectrl:
.LFB8:
	.cfi_startproc
	ldr	w0, [x0]
	mov	w0, 1
	str	w0, [x1]
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
	ldr	w0, [x0]
	cmp	w0, 1
	bne	.L11
.L13:
	str	w0, [x1]
	ret
.L11:
	mov	w0, 2
	b	.L13
	.cfi_endproc
.LFE9:
	.size	lb_realctrl, .-lb_realctrl
	.align	2
	.global	lb_shiftmask
	.type	lb_shiftmask, %function
lb_shiftmask:
.LFB23:
	.cfi_startproc
	b	lb_xor
	.cfi_endproc
.LFE23:
	.size	lb_shiftmask, .-lb_shiftmask
	.align	2
	.global	lb_modone
	.type	lb_modone, %function
lb_modone:
.LFB25:
	.cfi_startproc
	b	lb_xor
	.cfi_endproc
.LFE25:
	.size	lb_modone, .-lb_modone
	.ident	"GCC: (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"
	.section	.note.GNU-stack,"",@progbits
