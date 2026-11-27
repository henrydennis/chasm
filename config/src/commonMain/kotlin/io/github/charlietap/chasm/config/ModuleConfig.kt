package io.github.charlietap.chasm.config

data class ModuleConfig(
    val profile: Profile = Profile.Default,
    val proposals: Set<Proposal> = Proposal.entries.toSet() - Proposal.StackSwitching,
    val decodeNameSection: Boolean = false,
)
