package com.laragon.android.ui.main

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.laragon.android.R

/**
 * RecyclerView adapter for displaying the list of projects.
 */
class ProjectAdapter(
    private val onClick: (ProjectItem) -> Unit
) : ListAdapter<ProjectItem, ProjectAdapter.ProjectViewHolder>(ProjectDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project, parent, false)
        return ProjectViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ProjectViewHolder(
        itemView: View,
        private val onClick: (ProjectItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tv_project_name)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_project_status)
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_project_icon)

        fun bind(project: ProjectItem) {
            tvName.text = project.name
            tvStatus.text = if (project.hasIndex) "Ready" else "No index file"
            ivIcon.setImageResource(
                if (project.hasIndex) android.R.drawable.ic_menu_info_details
                else android.R.drawable.ic_menu_help
            )
            itemView.setOnClickListener { onClick(project) }
        }
    }

    companion object ProjectDiffCallback : DiffUtil.ItemCallback<ProjectItem>() {
        override fun areItemsTheSame(oldItem: ProjectItem, newItem: ProjectItem): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: ProjectItem, newItem: ProjectItem): Boolean {
            return oldItem == newItem
        }
    }
}
