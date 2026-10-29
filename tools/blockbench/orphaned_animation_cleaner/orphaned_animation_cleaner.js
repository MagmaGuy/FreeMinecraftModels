(function() {

	const PLUGIN_ID = 'orphaned_animation_cleaner';
	const ACTION_ID = 'clear_orphaned_animation_data';
	const ICON = 'cleaning_services';
	/*
	 * Blockbench's animation undo merges the saved animators back into each animation and never
	 * deletes one, so a plain redo would leave the removed animators in place. The "after" save of
	 * a cleanup carries this aspect with the animators it removed, and loading that save removes
	 * them again.
	 */
	const UNDO_ASPECT = 'orphaned_animators_removed';

	let action;
	let toast = null;
	// Project -> signature of the orphans the last toast announced, so the same set is announced once
	const announced = new WeakMap();

	/*
	 * Bone animators whose UUID matches no node in the outliner. An animator named like a bone that
	 * has no animator of its own is left alone: Animation.getBoneAnimator rebinds it to that bone
	 * when the animation is opened.
	 */
	function findOrphans() {
		let orphans = [];
		for (let animation of Animation.all) {
			let rebound = new Set();
			for (let uuid in animation.animators) {
				let animator = animation.animators[uuid];
				if (!(animator instanceof BoneAnimator) || animator.type !== 'bone') continue;
				if (OutlinerNode.uuids[uuid]) continue;

				let name = animator.name || '';
				let lowercase_name = name.toLowerCase();
				let rebind_target = name && Group.all.find(group => {
					return group.name.toLowerCase() === lowercase_name && !animation.animators[group.uuid] && !rebound.has(group);
				});
				if (rebind_target) {
					rebound.add(rebind_target);
					continue;
				}
				orphans.push({animation, uuid, name, keyframes: animator.keyframes.length});
			}
		}
		return orphans;
	}

	function plural(count, word) {
		return `${count} ${word}${count === 1 ? '' : 's'}`;
	}
	function summarize(orphans) {
		let bones = new Set(orphans.map(orphan => orphan.uuid)).size;
		let animations = new Set(orphans.map(orphan => orphan.animation)).size;
		let keyframes = orphans.reduce((sum, orphan) => sum + orphan.keyframes, 0);
		return `${plural(bones, 'missing bone')} in ${plural(animations, 'animation')} (${plural(keyframes, 'keyframe')})`;
	}
	function listOrphans(orphans) {
		let lines = [];
		for (let animation of Animation.all) {
			let entries = orphans.filter(orphan => orphan.animation === animation);
			if (!entries.length) continue;
			let bones = entries.map(orphan => `\`${orphan.name || orphan.uuid}\` (${plural(orphan.keyframes, 'keyframe')})`);
			lines.push(`- **${animation.name}**: ${bones.join(', ')}`);
		}
		return lines.join('\n');
	}
	function signature(orphans) {
		return orphans.map(orphan => `${orphan.animation.uuid}/${orphan.uuid}/${orphan.keyframes}`).sort().join('|');
	}

	function clearOrphans(orphans) {
		let animations = [...new Set(orphans.map(orphan => orphan.animation))];
		let removed = {};
		for (let orphan of orphans) {
			if (!removed[orphan.animation.uuid]) removed[orphan.animation.uuid] = [];
			removed[orphan.animation.uuid].push(orphan.uuid);
		}
		Undo.initEdit({animations});
		for (let orphan of orphans) {
			orphan.animation.removeAnimator(orphan.uuid);
		}
		Undo.finishEdit('Clear orphaned animation data', {animations, [UNDO_ASPECT]: removed});
	}
	function clearAndReport() {
		let orphans = findOrphans();
		hideToast();
		if (!orphans.length) {
			Blockbench.showQuickMessage('No orphaned animation data found', 2000);
			return;
		}
		clearOrphans(orphans);
		Blockbench.showMessageBox({
			title: 'Orphaned animation data cleared',
			icon: ICON,
			message: `Removed the animation data of ${summarize(orphans)}. Undo restores it.\n\n${listOrphans(orphans)}`,
		});
	}

	function onCreateUndoSave({save, aspects}) {
		if (aspects && aspects[UNDO_ASPECT]) {
			save[UNDO_ASPECT] = JSON.parse(JSON.stringify(aspects[UNDO_ASPECT]));
		}
	}
	function onLoadUndoSave({save}) {
		let removed = save[UNDO_ASPECT];
		if (!removed) return;
		for (let animation_uuid in removed) {
			let animation = Animation.all.find(animation => animation.uuid === animation_uuid);
			if (!animation) continue;
			for (let uuid of removed[animation_uuid]) {
				// An animator whose bone exists again is live data
				if (animation.animators[uuid] && !OutlinerNode.uuids[uuid]) {
					animation.removeAnimator(uuid);
				}
			}
		}
	}

	function hideToast() {
		if (toast) toast.handle.delete();
		toast = null;
	}
	function checkProject() {
		if (!Project || !Format || !Format.animation_mode) return;
		let orphans = findOrphans();
		if (!orphans.length) {
			if (toast && toast.project === Project) hideToast();
			return;
		}
		let orphan_signature = signature(orphans);
		if (announced.get(Project) === orphan_signature) return;
		announced.set(Project, orphan_signature);

		hideToast();
		let project = Project;
		let handle = Blockbench.showToastNotification({
			icon: ICON,
			text: `${Project.name}: found animation data for ${summarize(orphans)}. Click to clear it.`,
			click() {
				if (Project === project) clearAndReport();
				return true;
			},
			// Blockbench 5.2 and later
			onClose() {
				if (toast && toast.handle === handle) toast = null;
			}
		});
		toast = {handle, project};
	}
	function onUnselectProject({project}) {
		if (toast && toast.project === project) {
			// Announce again when the user returns to that project
			announced.delete(project);
			hideToast();
		}
	}
	function onParsed() {
		checkProject();
	}

	BBPlugin.register(PLUGIN_ID, {
		title: 'Orphaned Animation Cleaner',
		author: 'Nightbreak',
		description: 'Finds animation data left behind by deleted bones and clears it.',
		about: 'Blockbench keeps the keyframes of a deleted bone in every animation, hidden, because a deleted bone cannot be selected. ' +
			'This plugin checks for that data when a model loads or an animation opens and offers to clear it. ' +
			'Run it any time from **Animation > Clear Orphaned Animation Data** or **Tools > Clear Orphaned Animation Data**. Clearing can be undone.',
		icon: ICON,
		version: '1.0.0',
		min_version: '5.0.0',
		variant: 'both',
		tags: ['Animation'],
		onload() {
			action = new Action(ACTION_ID, {
				name: 'Clear Orphaned Animation Data',
				description: 'Remove animation data left behind by deleted bones',
				icon: ICON,
				category: 'animation',
				condition: () => Project && Format && Format.animation_mode,
				click() {
					let orphans = findOrphans();
					if (!orphans.length) {
						Blockbench.showQuickMessage('No orphaned animation data found', 2000);
						return;
					}
					Blockbench.showMessageBox({
						title: 'Orphaned animation data',
						icon: ICON,
						message: `Found animation data for ${summarize(orphans)}. Blockbench cannot show it because those bones no longer exist.\n\n${listOrphans(orphans)}`,
						buttons: ['Clear', 'dialog.cancel'],
						confirm: 0,
						cancel: 1,
					}, button => {
						if (button === 0) clearAndReport();
					});
				}
			});
			MenuBar.addAction(action, 'animation');
			MenuBar.addAction(action, 'tools');

			Blockbench.on('create_undo_save', onCreateUndoSave);
			Blockbench.on('load_undo_save', onLoadUndoSave);
			Blockbench.on('select_animation', checkProject);
			Blockbench.on('select_project', checkProject);
			Blockbench.on('unselect_project', onUnselectProject);
			Codecs.project.on('parsed', onParsed);

			checkProject();
		},
		onunload() {
			action.delete();
			Blockbench.removeListener('create_undo_save', onCreateUndoSave);
			Blockbench.removeListener('load_undo_save', onLoadUndoSave);
			Blockbench.removeListener('select_animation', checkProject);
			Blockbench.removeListener('select_project', checkProject);
			Blockbench.removeListener('unselect_project', onUnselectProject);
			Codecs.project.removeListener('parsed', onParsed);
			hideToast();
		}
	});

})();
