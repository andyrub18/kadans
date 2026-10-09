using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Identity.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class DeviceReminderSync : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "reminders_synced_at",
                schema: "identity",
                table: "devices",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "reminders_through",
                schema: "identity",
                table: "devices",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<long>(
                name: "reminders_version",
                schema: "identity",
                table: "devices",
                type: "bigint",
                nullable: false,
                defaultValue: 0L);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "reminders_synced_at",
                schema: "identity",
                table: "devices");

            migrationBuilder.DropColumn(
                name: "reminders_through",
                schema: "identity",
                table: "devices");

            migrationBuilder.DropColumn(
                name: "reminders_version",
                schema: "identity",
                table: "devices");
        }
    }
}
