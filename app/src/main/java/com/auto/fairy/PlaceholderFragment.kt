package com.auto.fairy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.auto.fairy.databinding.FragmentPlaceholderBinding

/**
 * 空占位页：标题 + 一句提示，内容由调用方传入。
 *
 * 三个功能页（诊断/保活/设置）都已有真实实现；本类只剩「长按顶部入口」这条
 * 兜底路径在用（浮层卡片不可用时给个可见落点），不再是它们的主体。
 */
class PlaceholderFragment : Fragment() {

    private var binding: FragmentPlaceholderBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = FragmentPlaceholderBinding.inflate(inflater, container, false)
        binding = view
        return view.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = requireArguments().getInt(ARG_TITLE)
        val hint = requireArguments().getInt(ARG_HINT)
        binding?.textPlaceholderTitle?.setText(title)
        binding?.textPlaceholderHint?.setText(hint)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_HINT = "hint"

        fun newInstance(@StringRes title: Int, @StringRes hint: Int): PlaceholderFragment =
            PlaceholderFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, title)
                    putInt(ARG_HINT, hint)
                }
            }
    }
}
